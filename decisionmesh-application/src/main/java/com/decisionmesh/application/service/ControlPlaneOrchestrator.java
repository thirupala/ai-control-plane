package com.decisionmesh.application.service;


import com.decisionmesh.application.exception.PolicyViolationException;
import com.decisionmesh.application.exception.DuplicateSubmissionException;
import com.decisionmesh.application.exception.LockExtensionFailedException;
import com.decisionmesh.application.exception.RateLimitExceededException;
import com.decisionmesh.application.idempotency.IdempotencyService;
import com.decisionmesh.application.lock.LockManager;
import com.decisionmesh.application.lock.LockToken;
import com.decisionmesh.application.policy.PolicyEvaluationResult;
import com.decisionmesh.application.port.*;
import com.decisionmesh.application.ratelimit.RateLimiter;
import com.decisionmesh.application.reconciliation.ReconciliationService;
import com.decisionmesh.application.telemetry.TelemetryPublisher;
import com.decisionmesh.domain.event.DomainEvent;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.plan.Plan;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ControlPlaneOrchestrator {

    @Inject
    Planner planner;

    @Inject
    ExecutionEngine executionEngine;

    @Inject
    PolicyEngine policyEngine;

    @Inject
    LearningEngine learningEngine;

    @Inject
    BudgetGuard budgetGuard;

    @Inject
    IntentCentricSLAGuard slaGuard;

    @Inject
    RateLimiter rateLimiter;

    @Inject
    IntentRepositoryPort intentRepository;

    @Inject
    ExecutionRepositoryPort executionRepository;

    @Inject
    IntentEventRepositoryPort eventRepository;

    @Inject
    LockManager lockManager;

    @Inject
    IdempotencyService idempotencyService;

    @Inject
    TelemetryPublisher telemetry;

    @Inject
    ReconciliationService reconciliationService;

    @Inject
    PlanRepositoryPort planRepository;

    @ConfigProperty(name = "controlplane.lock.intent-ttl-minutes", defaultValue = "5")
    int lockTtlMinutes;

    @ConfigProperty(name = "controlplane.lock.max-retries", defaultValue = "5")
    int lockMaxRetries;

    /**
     * Submit a new intent to the control plane.
     *
     * @param intent          The intent aggregate (already validated by API layer)
     * @param idempotencyKey  Caller-supplied idempotency key
     * @param intentType      Intent type for rate limit scoping
     * @return Intent UUID if accepted
     */
    public Uni<UUID> submit(Intent intent, UUID tenantId, String idempotencyKey, String intentType) {

        Log.infof("Intent submission: id=%s, tenant=%s, type=%s, idempotency=%s",
                intent.getId(), intent.getTenantId(), intentType, idempotencyKey);

        // Step 1: Idempotency check (early return if duplicate)
        return idempotencyService.checkAndRegister(tenantId, idempotencyKey)
                .flatMap(allowed -> {
                    if (!allowed) {
                        Log.warnf("Duplicate intent submission blocked: idempotency=%s", idempotencyKey);
                        return Uni.createFrom().failure(
                                new DuplicateSubmissionException("Duplicate request: " + idempotencyKey)
                        );
                    }

                    // Step 2: Rate limit enforcement (PRE_SUBMISSION gate)
                    return rateLimiter.enforce(tenantId, intentType)
                            .flatMap(rateLimitOk -> {
                                if (!rateLimitOk) {
                                    Log.warnf("Rate limit exceeded: tenant=%s, type=%s", tenantId, intentType);
                                    return Uni.createFrom().failure(
                                            new RateLimitExceededException("Rate limit exceeded")
                                    );
                                }

                                // Step 3: Process intent with distributed lock
                                return  processIntentWithLock(intent, tenantId)
                                        .replaceWith(intent.getId());
                            });
                })
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Intent submission failed: id=%s", intent.getId())
                );
    }

    public Uni<Intent> getById(UUID tenantId, UUID id){
        Log.debugf("Fetching intent: id=%s", id);
        return intentRepository.findById(tenantId,id);
    }


    /**
     * Process intent with distributed lock coordination.
     * Ensures only ONE orchestrator processes this intent at any time.
     */
    private Uni<Void> processIntentWithLock(Intent intent,UUID tenantId) {
        String partitionKey = buildPartitionKey(intent,tenantId);

        // Acquire lock with retry (handles transient Redis failures)
        return lockManager.acquireWithRetry(
                        partitionKey,
                        Duration.ofMinutes(lockTtlMinutes),
                        lockMaxRetries,
                        Duration.ofMillis(100)
                )
                .flatMap(lockToken ->
                        // Execute workflow, always release lock (even on failure)
                        processIntentWorkflow(intent, lockToken)
                                .eventually(() -> releaseLockSafely(lockToken))
                )
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Intent processing failed: id=%s, partition=%s",
                                intent.getId(), partitionKey)
                );
    }

    /**
     * Main intent workflow: SUBMITTED → PLANNING → EXECUTING → EVALUATING → SATISFIED/VIOLATED
     */
    private Uni<Void> processIntentWorkflow(Intent intent, LockToken lockToken) {

        return Uni.createFrom().voidItem()

                .flatMap(v -> policyEngine.evaluatePreSubmission(intent))
                .invoke(this::assertPolicyAllowed)

                .flatMap(v -> budgetGuard.validateBudget(intent))

                .flatMap(v -> intentRepository.save(intent))

                .invoke(() -> intent.startPlanning())

                .flatMap(v -> planner.plan(intent))
                .flatMap(plan -> planRepository.save(plan).replaceWith(plan))

                .flatMap(plan -> {
                    intent.markExecuting();
                    return intentRepository.save(intent).replaceWith(plan);
                })

                .flatMap(plan -> executeWithGovernanceAndRetry(intent, plan, lockToken, 1))

                .invoke(() -> intent.markEvaluating())

                .flatMap(record ->
                        policyEngine.evaluatePostExecution(intent, record)
                                .invoke(this::assertPolicyAllowed)
                                .replaceWith(record)
                )

                .flatMap(record -> finalizeIntent(intent, record))

                .flatMap(v -> persistFinalState(intent));
    }


    /**
     * Execute plan with governance checks, retry logic, and SLA enforcement.
     */
    private Uni<ExecutionRecord> executeWithGovernanceAndRetry(
            Intent intent,
            Plan plan,
            LockToken lockToken,
            int attemptNumber
    ) {

        Log.debugf("Execution attempt %d/%d: intent=%s, plan=%s",
                attemptNumber,
                intent.getMaxRetries(),
                intent.getId(),
                plan.getPlanId());

        return Uni.createFrom().voidItem()

                // 1️⃣ Ensure lock is valid
                .flatMap(v -> extendLockIfNeeded(lockToken))

                // 2️⃣ SLA pre-check
                .flatMap(v -> slaGuard.validateBeforeExecution(intent))

                // 3️⃣ PRE-EXECUTION policy
                .flatMap(v -> policyEngine.evaluatePreExecution(intent))
                .invoke(this::assertPolicyAllowed)

                // 4️⃣ Execute adapter plan
                .flatMap(v -> executionEngine.execute(plan, attemptNumber))

                // 5️⃣ Persist execution record immediately (append-only)
                .flatMap(record ->
                        executionRepository.append(record)
                                .replaceWith(record)
                )

                // 6️⃣ SLA post-check
                .flatMap(record ->
                        slaGuard.validateAfterExecution(intent, record)
                                .replaceWith(record)
                )

                // 7️⃣ Retry logic
                .onFailure().recoverWithUni(ex -> {

                    Log.warnf(ex,
                            "Execution attempt %d failed: intent=%s",
                            attemptNumber,
                            intent.getId());

                    // Retry exhausted
                    if (attemptNumber >= intent.getMaxRetries()) {

                        Log.errorf("Retry exhausted: intent=%s, attempts=%d",
                                intent.getId(),
                                attemptNumber);

                        intent.markViolated();
                        return intentRepository.save(intent)
                                .flatMap(v -> Uni.createFrom().failure(ex));
                    }

                    // Schedule retry
                    intent.scheduleRetry();

                    return intentRepository.save(intent)
                            .flatMap(v ->
                                    executeWithGovernanceAndRetry(
                                            intent,
                                            plan,
                                            lockToken,
                                            attemptNumber + 1
                                    )
                            );
                });
    }


    /**
     * Extend lock if remaining TTL is low (< 1 minute).
     * Prevents lock expiry during long multi-step executions.
     */
    private Uni<Void> extendLockIfNeeded(LockToken lockToken) {
        long remainingMs = lockToken.remainingMillis();

        if (remainingMs < 60_000) { // Less than 1 minute remaining
            Log.infof("Extending lock: partition=%s, remaining=%dms",
                    lockToken.partitionKey(), remainingMs);

            return lockManager.extend(lockToken, Duration.ofMinutes(2))
                    .flatMap(extended -> {
                        if (!extended) {
                            return Uni.createFrom().failure(
                                    new LockExtensionFailedException(
                                            "Lock extension failed: " + lockToken.partitionKey()
                                    )
                            );
                        }
                        Log.infof("Lock extended: partition=%s", lockToken.partitionKey());
                        return Uni.createFrom().voidItem();
                    });
        }

        return Uni.createFrom().voidItem();
    }

    /**
     * Finalize intent based on execution result.
     */
    @Transactional
    protected Uni<Void> finalizeIntent(Intent intent, ExecutionRecord executionRecord) {

        if (executionRecord.isSuccess()) {
            // Compute drift score (if evaluator is available)
            BigDecimal driftScore = computeDriftScore(intent, executionRecord);
            intent.updateDriftScore(driftScore, executionRecord.getId());

            // Mark SATISFIED
            intent.markSatisfied();

            Log.infof("Intent SATISFIED: id=%s, cost=$%.6f, drift=%.4f, attempts=%d",
                    intent.getId(), executionRecord.getCostUsd(), driftScore,
                    executionRecord.getAttemptNumber());

        } else {
            // Mark VIOLATED
            intent.markViolated();

            Log.warnf("Intent VIOLATED: id=%s, reason=%s",
                    intent.getId(), executionRecord.getFailureReason());
        }

        return Uni.createFrom().voidItem();
    }

    /**
     * Persist final intent state + execution records + domain events.
     */
    private Uni<Void> persistFinalState(Intent intent) {

        List<DomainEvent> events = intent.pullDomainEvents();

        return intentRepository.save(intent)
                .flatMap(v -> eventRepository.appendAll(events))
                .flatMap(v -> learningEngine.updateProfiles(intent.getId()))
                .flatMap(v -> publishTelemetry(intent))
                .replaceWithVoid();
    }




    /**
     * Safely release lock with token validation.
     * Returns success/failure but does NOT throw.
     */
    private Uni<Void> releaseLockSafely(LockToken lockToken) {
        return lockManager.release(lockToken)
                .invoke(released -> {
                    if (released) {
                        Log.infof("Lock released: partition=%s, held_for=%dms",
                                lockToken.partitionKey(),
                                System.currentTimeMillis() - lockToken.acquiredAt().toEpochMilli());
                    } else {
                        Log.warnf("Lock release failed (already expired or stolen): partition=%s",
                                lockToken.partitionKey());
                    }
                })
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Lock release error: partition=%s", lockToken.partitionKey())
                )
                .onFailure().recoverWithItem(false) // Don't fail workflow on release error
                .replaceWithVoid();
    }

    /**
     * Build partition key for distributed lock.
     * Format: "intent:{tenant_id}:{intent_id}"
     */
    private String buildPartitionKey(Intent intent,UUID tenantId) {
        return String.format("intent:%s:%s", tenantId, intent.getId());
    }

    /**
     * Assert policy evaluation result is ALLOWED.
     * Throws if VIOLATION + HARD_STOP.
     */
    private void assertPolicyAllowed(PolicyEvaluationResult evaluation) {
        if (evaluation.isBlocking()) {
            throw new PolicyViolationException(
                    evaluation.getPolicyId(),
                    evaluation.getBlockReason()
            );
        }

        if (evaluation.isWarning()) {
            Log.warnf("Policy warning: policy=%s, detail=%s",
                    evaluation.getPolicyId(),
                    evaluation.getEvaluationDetail());
        }
    }


    /**
     * Compute drift score (placeholder — delegate to IntentEvaluator).
     */
    private BigDecimal computeDriftScore(Intent intent, ExecutionRecord executionRecord) {
        // TODO: Delegate to IntentEvaluator service
        // For now, return 0.0 (no drift)
        return BigDecimal.ZERO;
    }

    /**
     * Publish telemetry event.
     */
    private Uni<Void> publishTelemetry(Intent intent) {
        return telemetry.publish(
                intent.getPhase(),
                intent.getTenantId(),
                intent.getId(),
                intent.getVersion()
        );
    }

}