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

/**
 * Central orchestrator for the intent processing pipeline.
 *
 * Pipeline:  idempotency → rate limit → distributed lock → workflow
 *
 * Workflow:  PRE_SUBMISSION policy
 *            → budget validation
 *            → persist (CREATED)      [+ drain events]
 *            → PLANNING               [persist + drain events]
 *            → plan + persist plan
 *            → PLANNED                [persist + drain events]
 *            → EXECUTING              [persist + drain events]
 *            → execution with governance + retry
 *            → EVALUATING             [persist + drain events]
 *            → POST_EXECUTION policy
 *            → SATISFIED | VIOLATED   [persist + drain events]
 */
@ApplicationScoped
public class ControlPlaneOrchestrator {

    // ─── Dependencies ────────────────────────────────────────────────────────

    @Inject Planner                    planner;
    @Inject ExecutionEngine            executionEngine;
    @Inject PolicyEngine               policyEngine;
    @Inject LearningEngine             learningEngine;
    @Inject BudgetGuard                budgetGuard;
    @Inject IntentCentricSLAGuard      slaGuard;
    @Inject RateLimiter                rateLimiter;
    @Inject IntentRepositoryPort       intentRepository;
    @Inject ExecutionRepositoryPort    executionRepository;
    @Inject IntentEventRepositoryPort  eventRepository;
    @Inject LockManager                lockManager;
    @Inject IdempotencyService         idempotencyService;
    @Inject TelemetryPublisher         telemetry;
    @Inject ReconciliationService      reconciliationService;
    @Inject PlanRepositoryPort         planRepository;

    // ─── Configuration ───────────────────────────────────────────────────────

    @ConfigProperty(name = "controlplane.lock.intent-ttl-minutes",    defaultValue = "5")
    int lockTtlMinutes;

    @ConfigProperty(name = "controlplane.lock.max-retries",           defaultValue = "5")
    int lockMaxRetries;

    @ConfigProperty(name = "controlplane.lock.initial-backoff-ms",    defaultValue = "100")
    int lockInitialBackoffMs;

    @ConfigProperty(name = "controlplane.lock.extend-threshold-ms",   defaultValue = "60000")
    long lockExtendThresholdMs;

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Submit a new intent to the control plane.
     *
     * Returns a Uni — callers must subscribe (or chain) for any work to execute.
     * @Transactional removed: JTA transactions are thread-bound and incompatible
     * with Mutiny's event-loop threading model.
     */
    public Uni<UUID> submit(Intent intent,
                            UUID tenantId,
                            String idempotencyKey,
                            String intentType) {

        Log.infof("Intent submission: id=%s, tenant=%s, type=%s, idempotency=%s",
                intent.getId(), tenantId, intentType, idempotencyKey);

        // Pipeline — all steps chained; nothing blocks the event loop
        return idempotencyService.checkAndRegister(tenantId, idempotencyKey)
                .invoke(allowed -> {
                    if (!allowed) throw new DuplicateSubmissionException(
                            "Duplicate request: " + idempotencyKey);
                })
                .flatMap(v -> rateLimiter.enforce(tenantId, intentType))
                .invoke(ok -> {
                    if (!ok) throw new RateLimitExceededException(
                            "Rate limit exceeded for tenant: " + tenantId);
                })
                .flatMap(v -> {
                    if (intent.getId() == null) intent.setId(UUID.randomUUID());
                    return processIntentWithLock(intent, tenantId);
                })
                .replaceWith(intent.getId());
    }

    /**
     * Retrieve intent by ID, scoped to tenant.
     */
    public Uni<Intent> getById(UUID tenantId, UUID intentId) {
        Log.debugf("Fetching intent: id=%s, tenant=%s", intentId, tenantId);
        return intentRepository.findById(tenantId, intentId);
    }

    // ─── Lock coordination ───────────────────────────────────────────────────

    private Uni<Void> processIntentWithLock(Intent intent, UUID tenantId) {

        String partitionKey = buildPartitionKey(intent, tenantId);

        Log.infof("Acquiring lock: intent=%s, tenant=%s, partition=%s",
                intent.getId(), tenantId, partitionKey);

        return lockManager.exists(partitionKey)
                .invoke(exists -> Log.infof("Lock pre-check: partition=%s, alreadyExists=%s",
                        partitionKey, exists))
                .flatMap(ignored -> lockManager.acquireWithRetry(
                        partitionKey,
                        Duration.ofMinutes(lockTtlMinutes),
                        lockMaxRetries,
                        Duration.ofMillis(lockInitialBackoffMs)
                ))
                .flatMap(lockToken -> {
                    Log.infof("Lock acquired: partition=%s, intent=%s",
                            partitionKey, intent.getId());
                    return processIntentWorkflow(intent, lockToken)
                            .onTermination().call(() -> {
                                Log.infof("onTermination fired — releasing lock: partition=%s",
                                        partitionKey);
                                return releaseLockSafely(lockToken);
                            });
                })
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Intent processing failed: id=%s, partition=%s",
                                intent.getId(), partitionKey));
    }

    // ─── Workflow — full state machine ───────────────────────────────────────

    /**
     * Each phase transition:
     *   1. Mutate intent state (markX())
     *   2. Persist intent
     *   3. Drain and persist domain events immediately
     *
     * pullDomainEvents() is destructive — calling it twice returns empty on the
     * second call. Every transition drains the event queue right after it fires
     * so no events are silently lost.
     */
    private Uni<Void> processIntentWorkflow(Intent intent, LockToken lockToken) {

        return policyEngine.evaluatePreSubmission(intent)
                .invoke(this::assertPolicyAllowed)

                // Budget validation
                .flatMap(v -> budgetGuard.validateBudget(intent))

                // ── CREATED ──────────────────────────────────────────────────
                // intent.create() / constructor already set phase=CREATED and
                // raised the CREATED event — persist intent then drain events.
                .flatMap(v -> intentRepository.save(intent))
                .flatMap(v -> drainEvents(intent))

                // ── PLANNING ──────────────────────────────────────────────────
                .flatMap(v -> {
                    intent.startPlanning();
                    return intentRepository.save(intent);
                })
                .flatMap(v -> drainEvents(intent))

                // Plan
                .flatMap(v -> planner.plan(intent))
                .flatMap(plan -> planRepository.save(plan).replaceWith(plan))

                // ── PLANNED ───────────────────────────────────────────────────
                .flatMap(plan -> {
                    intent.markPlanned();
                    return intentRepository.save(intent)
                            .flatMap(v -> drainEvents(intent))
                            .replaceWith(plan);
                })

                // ── EXECUTING ─────────────────────────────────────────────────
                .flatMap(plan -> {
                    intent.markExecuting();
                    return intentRepository.save(intent)
                            .flatMap(v -> drainEvents(intent))
                            .replaceWith(plan);
                })

                // Execute
                .flatMap(plan -> executeWithGovernanceAndRetry(intent, plan, lockToken, 1))

                // ── EVALUATING ────────────────────────────────────────────────
                .flatMap(record -> {
                    intent.markEvaluating();
                    return intentRepository.save(intent)
                            .flatMap(v -> drainEvents(intent))
                            .replaceWith(record);
                })

                // Post-execution policy
                .flatMap(record ->
                        policyEngine.evaluatePostExecution(intent, record)
                                .invoke(this::assertPolicyAllowed)
                                .replaceWith(record))

                // ── SATISFIED | VIOLATED ──────────────────────────────────────
                .flatMap(record -> finalizeIntent(intent, record))

                // Persist final state + remaining events
                .flatMap(v -> persistFinalState(intent));
    }

    // ─── Execution with governance, SLA, and retry ───────────────────────────

    private Uni<ExecutionRecord> executeWithGovernanceAndRetry(
            Intent intent,
            Plan plan,
            LockToken lockToken,
            int attemptNumber) {

        Log.debugf("Execution attempt %d/%d: intent=%s, plan=%s",
                attemptNumber, intent.getMaxRetries(), intent.getId(), plan.getPlanId());

        return extendLockIfNeeded(lockToken)

                .flatMap(v -> slaGuard.validateBeforeExecution(intent))

                .flatMap(v -> policyEngine.evaluatePreExecution(intent))
                .invoke(this::assertPolicyAllowed)

                .flatMap(v -> executionEngine.execute(plan, attemptNumber))

                .flatMap(record ->
                        executionRepository.append(record)
                                .replaceWith(record))

                .flatMap(record ->
                        slaGuard.validateAfterExecution(intent, record)
                                .replaceWith(record))

                .onFailure().recoverWithUni(ex ->
                        handleExecutionFailure(intent, plan, lockToken, attemptNumber, ex));
    }

    /**
     * Retry logic:
     *   - Retries remain: scheduleRetry() + resumeExecution() atomically → single
     *     save → recurse. Two saves per retry was redundant.
     *   - Exhausted: markViolated() → save → propagate failure.
     */
    private Uni<ExecutionRecord> handleExecutionFailure(
            Intent intent,
            Plan plan,
            LockToken lockToken,
            int attemptNumber,
            Throwable ex) {

        Log.warnf(ex, "Execution attempt %d failed: intent=%s", attemptNumber, intent.getId());

        if (attemptNumber >= intent.getMaxRetries()) {
            Log.errorf("Retries exhausted (%d/%d): intent=%s",
                    attemptNumber, intent.getMaxRetries(), intent.getId());
            intent.markViolated();
            return intentRepository.save(intent)
                    .flatMap(v -> drainEvents(intent))
                    .flatMap(v -> Uni.createFrom().failure(ex));
        }

        // Transition atomically before a single save — avoids the double-save
        // pattern where resumeExecution() was called after the first persist.
        intent.scheduleRetry();
        intent.resumeExecution();

        return intentRepository.save(intent)
                .flatMap(v -> drainEvents(intent))
                .flatMap(v -> executeWithGovernanceAndRetry(
                        intent, plan, lockToken, attemptNumber + 1));
    }

    // ─── Lock lifecycle ───────────────────────────────────────────────────────

    private Uni<Void> extendLockIfNeeded(LockToken lockToken) {
        long remainingMs = lockToken.remainingMillis();

        if (remainingMs >= lockExtendThresholdMs) {
            return Uni.createFrom().voidItem();
        }

        Log.infof("Extending lock: partition=%s, remaining=%dms",
                lockToken.partitionKey(), remainingMs);

        return lockManager.extend(lockToken, Duration.ofMinutes(2))
                .flatMap(extended -> {
                    if (!extended) {
                        return Uni.createFrom().failure(
                                new LockExtensionFailedException(
                                        "Failed to extend lock: " + lockToken.partitionKey()));
                    }
                    Log.infof("Lock extended: partition=%s", lockToken.partitionKey());
                    return Uni.createFrom().voidItem();
                });
    }

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
                                lockToken.partitionKey()))
                .onFailure().recoverWithItem(false)
                .replaceWithVoid();
    }

    // ─── Finalization ─────────────────────────────────────────────────────────

    /**
     * Transition to SATISFIED or VIOLATED.
     * intent.phase must be EVALUATING when this is called.
     *
     * computeDriftScore() is not yet implemented — logs a warning so the
     * placeholder is visible in production rather than silently writing 0.0.
     */
    private Uni<Void> finalizeIntent(Intent intent, ExecutionRecord executionRecord) {
        if (executionRecord.isSuccess()) {
            BigDecimal driftScore = computeDriftScore(intent, executionRecord);
            intent.updateDriftScore(driftScore, executionRecord.getExecutionId());
            intent.markSatisfied();
            Log.infof("Intent SATISFIED: id=%s, cost=$%.6f, drift=%.4f, attempts=%d",
                    intent.getId(), executionRecord.getCost(), driftScore,
                    executionRecord.getAttemptNumber());
        } else {
            intent.markViolated();
            Log.warnf("Intent VIOLATED: id=%s, reason=%s",
                    intent.getId(), executionRecord.getFailureReason());
        }
        return Uni.createFrom().voidItem();
    }

    /**
     * Persist terminal state: intent + remaining domain events + learning + telemetry.
     * Domain events at this point are only the final SATISFIED/VIOLATED event —
     * all intermediate events were drained inline during workflow execution.
     */
    private Uni<Void> persistFinalState(Intent intent) {
        return intentRepository.save(intent)
                .flatMap(v -> drainEvents(intent))
                .flatMap(v -> learningEngine.updateProfiles(intent.getId()))
                .flatMap(v -> publishTelemetry(intent))
                .replaceWithVoid();
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    /**
     * Drain all pending domain events from the intent and persist them.
     * pullDomainEvents() is destructive — call exactly once per transition.
     * Returns voidItem immediately if there are no events to persist.
     */
    private Uni<Void> drainEvents(Intent intent) {
        List<DomainEvent> events = intent.pullDomainEvents();
        if (events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return eventRepository.appendAll(events);
    }

    private String buildPartitionKey(Intent intent, UUID tenantId) {
        return String.format("%s:%s", tenantId, intent.getId());
    }

    private void assertPolicyAllowed(PolicyEvaluationResult evaluation) {
        if (evaluation.isBlocking()) {
            throw new PolicyViolationException(
                    evaluation.getPolicyId(),
                    evaluation.getBlockReason());
        }
        if (evaluation.isWarning()) {
            Log.warnf("Policy warning: policy=%s, detail=%s",
                    evaluation.getPolicyId(),
                    evaluation.getEvaluationDetail());
        }
    }

    /**
     * TODO: Delegate to DriftEvaluatorService.
     * Returns BigDecimal.ZERO until implemented — logged as a warning so
     * the placeholder doesn't silently corrupt drift history.
     */
    private BigDecimal computeDriftScore(Intent intent, ExecutionRecord executionRecord) {
        Log.warnf("computeDriftScore not implemented — writing 0.0 for intent=%s",
                intent.getId());
        return BigDecimal.ZERO;
    }

    private Uni<Void> publishTelemetry(Intent intent) {
        return telemetry.publish(
                intent.getPhase(),
                intent.getTenantId(),
                intent.getId(),
                intent.getVersion());
    }
}