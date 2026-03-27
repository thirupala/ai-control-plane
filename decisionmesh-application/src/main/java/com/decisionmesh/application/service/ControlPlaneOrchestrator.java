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
import jakarta.transaction.Transactional;
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
 *            → persist (CREATED)
 *            → PLANNING   [persist]
 *            → plan + persist plan
 *            → PLANNED    [persist]          ← was missing: caused Invalid transition
 *            → EXECUTING  [persist]
 *            → execution with governance + retry
 *            → EVALUATING [in memory]
 *            → POST_EXECUTION policy
 *            → SATISFIED | VIOLATED
 *            → persist final state
 */
@ApplicationScoped
public class ControlPlaneOrchestrator {

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

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
    @Inject jakarta.persistence.EntityManager em;

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
     * Pipeline: idempotency → rate limit → distributed lock → workflow
     *
     * @param intent         Validated intent aggregate from API layer
     * @param tenantId       Tenant context from authenticated identity
     * @param idempotencyKey Caller-supplied deduplication key
     * @param intentType     Intent type for rate limit scoping
     * @return Intent UUID on acceptance
     */
    @Transactional
    public UUID submit(Intent intent,
                       UUID tenantId,
                       String idempotencyKey,
                       String intentType) {

        Log.infof("Intent submission: id=%s, tenant=%s, type=%s, idempotency=%s",
                intent.getId(), tenantId, intentType, idempotencyKey);

        // ✅ Idempotency (blocking)
        boolean allowed = idempotencyService
                .checkAndRegister(tenantId, idempotencyKey)
                .await().indefinitely();

        if (!allowed) {
            throw new DuplicateSubmissionException("Duplicate request: " + idempotencyKey);
        }

        // ✅ Rate limit (blocking)
        boolean rateLimitOk = rateLimiter
                .enforce(tenantId, intentType)
                .await().indefinitely();

        if (!rateLimitOk) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded for tenant: " + tenantId);
        }

        // ✅ Ensure ID
        if (intent.getId() == null) {
            intent.setId(UUID.randomUUID());
        }

        // ✅ Process workflow (blocking)
        processIntentWithLock(intent, tenantId)
                .await().indefinitely();

        return intent.getId();
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
     * run the full workflow, then ALWAYS release — on success or failure.
     *
     * Partition key: "{tenantId}:{intentId}"
     * Redis key:     "lock:intent:{tenantId}:{intentId}"  (prefix in RedisLockManager)
     */
    private Uni<Void> processIntentWithLock(Intent intent, UUID tenantId) {
        intent.setTenantId(tenantId);

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

    // -------------------------------------------------------------------------
    // Workflow — full state machine
    // -------------------------------------------------------------------------

    /**
     * Intent lifecycle:
     *
     *   [CREATED]   → pre-submission policy + budget validation + initial persist
     *   [PLANNING]  → persist → planner.plan() → persist plan
     *   [PLANNED]   → persist                            ← Fix: was missing, caused Invalid transition
     *   [EXECUTING] → persist → execution with governance/retry
     *   [EVALUATING]→ in-memory only (no persist — phase advances within same transaction)
     *   [COMPLETED] → SATISFIED or VIOLATED → persist final state
     *
     * Each phase is persisted before the work of that phase begins, so the DB
     * always reflects the in-progress state — crash recovery and reconciliation
     * can resume from the last persisted phase.
     */

    private Uni<Void> processIntentWorkflow(Intent intent, LockToken lockToken) {

        return policyEngine.evaluatePreSubmission(intent)
                .emitOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                .invoke(this::assertPolicyAllowed)

                // Budget validation
                .flatMap(v -> budgetGuard.validateBudget(intent))

                // 1. Persist intent FIRST
                .flatMap(v -> intentRepository.save(intent))
                .invoke(v -> em.flush())

                // 2. Immediately persist CREATED event
                .flatMap(v -> {
                    var events = intent.pullDomainEvents();
                    return eventRepository.appendAll(events);
                })

                // ── PLANNING phase ──────────────────────────────────────────
                .invoke(v -> intent.startPlanning())
                .flatMap(v -> intentRepository.save(intent))

                // Plan
                .flatMap(v -> planner.plan(intent))
                .flatMap(plan -> planRepository.save(plan).replaceWith(plan))

                // ── PLANNED phase ───────────────────────────────────────────
                .flatMap(plan -> {
                    intent.markPlanned();
                    return intentRepository.save(intent)
                            .replaceWith(plan);
                })

                // ── EXECUTING phase ─────────────────────────────────────────
                .flatMap(plan -> {
                    intent.markExecuting();
                    return intentRepository.save(intent)
                            .replaceWith(plan);
                })

                // Execute
                .flatMap(plan -> executeWithGovernanceAndRetry(intent, plan, lockToken, 1))

                // ── EVALUATING ──────────────────────────────────────────────
                .invoke(record -> intent.markEvaluating())

                // Post-execution policy
                .flatMap(record ->
                        policyEngine.evaluatePostExecution(intent, record)
                                .invoke(this::assertPolicyAllowed)
                                .replaceWith(record))

                // ── COMPLETED ───────────────────────────────────────────────
                .flatMap(record -> finalizeIntent(intent, record))

                // Persist final state (NO event append here)
                .flatMap(v -> persistFinalState(intent));
    }




    // -------------------------------------------------------------------------
    // Execution with governance, SLA, and retry
    // -------------------------------------------------------------------------

    /**
     * Runs one execution attempt with full governance wrapper:
     *   - Lock health check (extend TTL if low)
     *   - SLA pre-validation
     *   - Pre-execution policy gate
     *   - Adapter dispatch
     *   - Execution record persistence
     *   - SLA post-validation
     *   - Retry on failure (up to max_retries)
     */
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
     * Handle a failed execution attempt.
     *
     * If retries remain: scheduleRetry() → persist → recurse with attemptNumber+1
     * If exhausted:      markViolated()  → persist → propagate failure
     *
     * Note: markViolated() accepts both EXECUTING and EVALUATING phases
     * (retries exhausted mid-execution vs. policy block after evaluation).
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
                    .flatMap(v -> Uni.createFrom().failure(ex));
        }

        intent.scheduleRetry();

        return intentRepository.save(intent)
                .flatMap(v -> {
                    intent.resumeExecution();   //   FIX
                    return intentRepository.save(intent);
                })
                .flatMap(v -> executeWithGovernanceAndRetry(
                        intent, plan, lockToken, attemptNumber + 1));
    }

    // -------------------------------------------------------------------------
    // Lock lifecycle helpers
    // -------------------------------------------------------------------------

    /**
     * Extend lock TTL if remaining time is below the configured threshold.
     * Prevents expiry during long multi-step executions (e.g., slow LLM responses).
     */
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

    /**
     * Release lock safely — never throws, logs on failure.
     * Called from onTermination() so it fires on success, failure, and cancellation.
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
                                lockToken.partitionKey()))
                .onFailure().recoverWithItem(false)
                .replaceWithVoid();
    }

    // -------------------------------------------------------------------------
    // Finalization
    // -------------------------------------------------------------------------

    /**
     * Transition intent to SATISFIED or VIOLATED based on the execution outcome.
     *
     * At this point intent.phase == EVALUATING (set by markEvaluating() in workflow).
     * markSatisfied() and markViolated() both transition from EVALUATING → COMPLETED.
     */
    protected Uni<Void> finalizeIntent(Intent intent, ExecutionRecord executionRecord) {
        if (executionRecord.isSuccess()) {
            BigDecimal driftScore = computeDriftScore(intent, executionRecord);
            intent.updateDriftScore(driftScore, executionRecord.getId());
            intent.markSatisfied();                               // EVALUATING → COMPLETED (SATISFIED)
            Log.infof("Intent SATISFIED: id=%s, cost=$%.6f, drift=%.4f, attempts=%d",
                    intent.getId(), executionRecord.getCostUsd(), driftScore,
                    executionRecord.getAttemptNumber());
        } else {
            intent.markViolated();                                // EVALUATING → COMPLETED (VIOLATED)
            Log.warnf("Intent VIOLATED: id=%s, reason=%s",
                    intent.getId(), executionRecord.getFailureReason());
        }
        return Uni.createFrom().voidItem();
    }

    /**
     * Persist all terminal state: intent + domain events + learning profiles + telemetry.
     * Steps are sequential — failure in any step propagates upward.
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
     * Compute drift score for the executed intent.
     * TODO: Delegate to DriftEvaluatorService (comparison of objective vs. output).
     */
    private BigDecimal computeDriftScore(Intent intent, ExecutionRecord executionRecord) {
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