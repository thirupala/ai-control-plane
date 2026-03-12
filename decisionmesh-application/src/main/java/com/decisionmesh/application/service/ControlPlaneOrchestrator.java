package com.decisionmesh.application.service;

import com.decisionmesh.application.exception.DuplicateSubmissionException;
import com.decisionmesh.application.exception.LockExtensionFailedException;
import com.decisionmesh.application.exception.PolicyViolationException;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ControlPlaneOrchestrator {

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    @Inject Planner planner;
    @Inject ExecutionEngine executionEngine;
    @Inject PolicyEngine policyEngine;
    @Inject LearningEngine learningEngine;
    @Inject BudgetGuard budgetGuard;
    @Inject IntentCentricSLAGuard slaGuard;
    @Inject RateLimiter rateLimiter;
    @Inject IntentRepositoryPort intentRepository;
    @Inject ExecutionRepositoryPort executionRepository;
    @Inject IntentEventRepositoryPort eventRepository;
    @Inject LockManager lockManager;
    @Inject IdempotencyService idempotencyService;
    @Inject TelemetryPublisher telemetry;
    @Inject ReconciliationService reconciliationService;
    @Inject PlanRepositoryPort planRepository;

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    @ConfigProperty(name = "controlplane.lock.intent-ttl-minutes", defaultValue = "5")
    int lockTtlMinutes;

    @ConfigProperty(name = "controlplane.lock.max-retries", defaultValue = "5")
    int lockMaxRetries;

    @ConfigProperty(name = "controlplane.lock.initial-backoff-ms", defaultValue = "100")
    int lockInitialBackoffMs;

    @ConfigProperty(name = "controlplane.lock.extend-threshold-ms", defaultValue = "60000")
    long lockExtendThresholdMs;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Submit a new intent to the control plane.
     * Pipeline: idempotency → rate limit → lock → workflow
     *
     * @param intent         The intent aggregate (already validated by API layer)
     * @param tenantId       Tenant context from authenticated identity
     * @param idempotencyKey Caller-supplied idempotency key
     * @param intentType     Intent type for rate limit scoping
     * @return Intent UUID on acceptance
     */
    public Uni<UUID> submit(Intent intent, UUID tenantId, String idempotencyKey, String intentType) {

        Log.infof("Intent submission: id=%s, tenant=%s, type=%s, idempotency=%s",
                intent.getId(), tenantId, intentType, idempotencyKey);

        return idempotencyService.checkAndRegister(tenantId, idempotencyKey)
                .flatMap(allowed -> {
                    if (!allowed) {
                        Log.warnf("Duplicate intent blocked: idempotency=%s", idempotencyKey);
                        return Uni.createFrom().failure(
                                new DuplicateSubmissionException("Duplicate request: " + idempotencyKey)
                        );
                    }
                    return rateLimiter.enforce(tenantId, intentType);
                })
                .flatMap(rateLimitOk -> {
                    if (!rateLimitOk) {
                        Log.warnf("Rate limit exceeded: tenant=%s, type=%s", tenantId, intentType);
                        return Uni.createFrom().failure(
                                new RateLimitExceededException("Rate limit exceeded for tenant: " + tenantId)
                        );
                    }
                    return processIntentWithLock(intent, tenantId);
                })
                .replaceWith(intent.getId())
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Intent submission failed: id=%s, tenant=%s",
                                intent.getId(), tenantId)
                );
    }

    /**
     * Retrieve intent by ID, scoped to tenant.
     */
    public Uni<Intent> getById(UUID tenantId, UUID intentId) {
        Log.debugf("Fetching intent: id=%s, tenant=%s", intentId, tenantId);
        return intentRepository.findById(tenantId, intentId);
    }

    // -------------------------------------------------------------------------
    // Lock coordination
    // -------------------------------------------------------------------------

    /**
     * Acquire a distributed lock for this intent partition,
     * run the workflow, then ALWAYS release — success or failure.
     *
     * Lock key format: "{tenantId}:{intentId}"
     * Redis key becomes: "lock:{tenantId}:{intentId}"  (prefix added by RedisLockManager)
     */
    private Uni<Void> processIntentWithLock(Intent intent, UUID tenantId) {
        String partitionKey = buildPartitionKey(intent, tenantId);

        return lockManager.acquireWithRetry(
                        partitionKey,
                        Duration.ofMinutes(lockTtlMinutes),
                        lockMaxRetries,
                        Duration.ofMillis(lockInitialBackoffMs)
                )
                .flatMap(lockToken ->
                        processIntentWorkflow(intent, lockToken)
                                // eventually() = always runs: success, failure, AND cancellation
                                .eventually(() -> releaseLockSafely(lockToken))
                )
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Intent processing failed: id=%s, partition=%s",
                                intent.getId(), partitionKey)
                );
    }

    // -------------------------------------------------------------------------
    // Workflow
    // -------------------------------------------------------------------------

    /**
     * Main intent workflow:
     * SUBMITTED → PLANNING → EXECUTING → EVALUATING → SATISFIED | VIOLATED
     */
    private Uni<Void> processIntentWorkflow(Intent intent, LockToken lockToken) {
        return policyEngine.evaluatePreSubmission(intent)
                .invoke(this::assertPolicyAllowed)

                .flatMap(v -> budgetGuard.validateBudget(intent))

                .flatMap(v -> intentRepository.save(intent))

                .invoke(v -> intent.startPlanning())

                .flatMap(v -> planner.plan(intent))
                .flatMap(plan -> planRepository.save(plan).replaceWith(plan))

                .flatMap(plan -> {
                    intent.markExecuting();
                    return intentRepository.save(intent).replaceWith(plan);
                })

                .flatMap(plan -> executeWithGovernanceAndRetry(intent, plan, lockToken, 1))

                .invoke(v -> intent.markEvaluating())

                .flatMap(record ->
                        policyEngine.evaluatePostExecution(intent, record)
                                .invoke(this::assertPolicyAllowed)
                                .replaceWith(record)
                )

                .flatMap(record -> finalizeIntent(intent, record))

                .flatMap(v -> persistFinalState(intent));
    }

    // -------------------------------------------------------------------------
    // Execution with governance, SLA, and retry
    // -------------------------------------------------------------------------

    /**
     * Execute plan with:
     * - Lock health check (extend if TTL low)
     * - SLA pre/post validation
     * - Pre-execution policy gate
     * - Retry with exponential-backoff semantics
     */
    private Uni<ExecutionRecord> executeWithGovernanceAndRetry(
            Intent intent,
            Plan plan,
            LockToken lockToken,
            int attemptNumber
    ) {
        Log.debugf("Execution attempt %d/%d: intent=%s, plan=%s",
                attemptNumber, intent.getMaxRetries(), intent.getId(), plan.getPlanId());

        return extendLockIfNeeded(lockToken)

                .flatMap(v -> slaGuard.validateBeforeExecution(intent))

                .flatMap(v -> policyEngine.evaluatePreExecution(intent))
                .invoke(this::assertPolicyAllowed)

                .flatMap(v -> executionEngine.execute(plan, attemptNumber))

                .flatMap(record ->
                        executionRepository.append(record)
                                .replaceWith(record)
                )

                .flatMap(record ->
                        slaGuard.validateAfterExecution(intent, record)
                                .replaceWith(record)
                )

                .onFailure().recoverWithUni(ex -> handleExecutionFailure(
                        intent, plan, lockToken, attemptNumber, ex
                ));
    }

    /**
     * Handle execution failure: retry or mark VIOLATED.
     */
    private Uni<ExecutionRecord> handleExecutionFailure(
            Intent intent,
            Plan plan,
            LockToken lockToken,
            int attemptNumber,
            Throwable ex
    ) {
        Log.warnf(ex, "Execution attempt %d failed: intent=%s", attemptNumber, intent.getId());

        if (attemptNumber >= intent.getMaxRetries()) {
            Log.errorf("Retries exhausted: intent=%s, attempts=%d", intent.getId(), attemptNumber);
            intent.markViolated();
            return intentRepository.save(intent)
                    .flatMap(v -> Uni.createFrom().failure(ex));
        }

        intent.scheduleRetry();
        return intentRepository.save(intent)
                .flatMap(v -> executeWithGovernanceAndRetry(
                        intent, plan, lockToken, attemptNumber + 1
                ));
    }

    // -------------------------------------------------------------------------
    // Lock lifecycle helpers
    // -------------------------------------------------------------------------

    /**
     * Extend lock TTL when remaining time is below the configured threshold.
     * Prevents expiry during long multi-step executions.
     */
    private Uni<Void> extendLockIfNeeded(LockToken lockToken) {
        long remainingMs = lockToken.remainingMillis();

        if (remainingMs < lockExtendThresholdMs) {
            Log.infof("Extending lock: partition=%s, remaining=%dms",
                    lockToken.partitionKey(), remainingMs);

            return lockManager.extend(lockToken, Duration.ofMinutes(2))
                    .flatMap(extended -> {
                        if (!extended) {
                            return Uni.createFrom().failure(
                                    new LockExtensionFailedException(
                                            "Failed to extend lock: " + lockToken.partitionKey()
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
     * Release lock safely — never throws, logs warnings on failure.
     * Called from eventually() so it always runs regardless of workflow outcome.
     */
    private Uni<Void> releaseLockSafely(LockToken lockToken) {
        return lockManager.release(lockToken)
                .invoke(released -> {
                    if (released) {
                        Log.infof("Lock released: partition=%s, held_for=%dms",
                                lockToken.partitionKey(),
                                System.currentTimeMillis() - lockToken.acquiredAt().toEpochMilli());
                    } else {
                        Log.warnf("Lock already expired or stolen: partition=%s",
                                lockToken.partitionKey());
                    }
                })
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Lock release error (non-fatal): partition=%s",
                                lockToken.partitionKey())
                )
                .onFailure().recoverWithItem(false)
                .replaceWithVoid();
    }

    // -------------------------------------------------------------------------
    // Finalization
    // -------------------------------------------------------------------------

    /**
     * Mark intent SATISFIED or VIOLATED based on execution result.
     * Uses @WithTransaction (reactive-safe replacement for @Transactional).
     */
    protected Uni<Void> finalizeIntent(Intent intent, ExecutionRecord executionRecord) {
        if (executionRecord.isSuccess()) {
            BigDecimal driftScore = computeDriftScore(intent, executionRecord);
            intent.updateDriftScore(driftScore, executionRecord.getId());
            intent.markSatisfied();
            Log.infof("Intent SATISFIED: id=%s, cost=$%.6f, drift=%.4f, attempts=%d",
                    intent.getId(), executionRecord.getCostUsd(), driftScore,
                    executionRecord.getAttemptNumber());
        } else {
            intent.markViolated();
            Log.warnf("Intent VIOLATED: id=%s, reason=%s",
                    intent.getId(), executionRecord.getFailureReason());
        }
        return Uni.createFrom().voidItem();
    }

    /**
     * Persist final state: intent + domain events + learning profiles + telemetry.
     * All steps are sequential — a failure in any step propagates upward.
     */
    private Uni<Void> persistFinalState(Intent intent) {
        List<DomainEvent> events = intent.pullDomainEvents();

        return intentRepository.save(intent)
                .flatMap(v -> eventRepository.appendAll(events))
                .flatMap(v -> learningEngine.updateProfiles(intent.getId()))
                .flatMap(v -> publishTelemetry(intent))
                .replaceWithVoid();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Build partition key for distributed lock.
     *
     * Format:  "{tenantId}:{intentId}"
     * RedisLockManager prepends "lock:" → final key: "lock:{tenantId}:{intentId}"
     */
    private String buildPartitionKey(Intent intent, UUID tenantId) {
        return String.format("%s:%s", tenantId, intent.getId());
    }

    /**
     * Assert policy result is ALLOWED. Throws on HARD_STOP violation.
     * Logs warnings for soft violations.
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
     * Compute drift score for the executed intent.
     * TODO: Delegate to IntentEvaluator service.
     */
    private BigDecimal computeDriftScore(Intent intent, ExecutionRecord executionRecord) {
        return BigDecimal.ZERO;
    }

    /**
     * Publish telemetry event for observability pipeline.
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