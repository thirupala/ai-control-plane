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
import com.decisionmesh.contracts.security.guard.PromptInjectionGuardService;
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
 *            → persist (CREATED)           [+ drain events]
 *            → PLANNING                    [+ drain events]
 *            → injection guard             [blocks CRITICAL injections]
 *            → plan + persist plan
 *            → PLANNED                     [+ drain events]
 *            → EXECUTING                   [+ drain events]
 *            → execution with governance + retry
 *            → EVALUATING                  [+ drain events]
 *            → quality scoring             [scores response, detects hallucination]
 *            → post-execution policy
 *            → drift scoring               [async, replaces computeDriftScore stub]
 *            → SATISFIED | VIOLATED        [+ drain events]
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

    // ── New services ──────────────────────────────────────────────────────────
    @Inject PromptInjectionGuardService injectionGuard;   // decisionmesh-security
    @Inject OutputQualityScorerService  qualityScorer;    // decisionmesh-intelligence
    @Inject DriftEvaluatorService       driftEvaluator;   // decisionmesh-intelligence

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
     * @Transactional removed: JTA transactions are thread-bound and incompatible
     * with Mutiny's event-loop threading model.
     */
    public Uni<UUID> submit(Intent intent,
                            UUID tenantId,
                            String idempotencyKey,
                            String intentType) {

        Log.infof("Intent submission: id=%s, tenant=%s, type=%s, idempotency=%s",
                intent.getId(), tenantId, intentType, idempotencyKey);

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

    private Uni<Void> processIntentWorkflow(Intent intent, LockToken lockToken) {

        return policyEngine.evaluatePreSubmission(intent)
                .invoke(this::assertPolicyAllowed)

                // Budget validation
                .flatMap(v -> budgetGuard.validateBudget(intent))

                // ── CREATED ──────────────────────────────────────────────────
                .flatMap(v -> intentRepository.save(intent))
                .flatMap(v -> drainEvents(intent))

                // ── PLANNING ──────────────────────────────────────────────────
                .flatMap(v -> {
                    intent.startPlanning();
                    return intentRepository.save(intent);
                })
                .flatMap(v -> drainEvents(intent))

                // ── INJECTION GUARD ───────────────────────────────────────────
                // Scans intent.getObjective() for prompt injection patterns.
                // CRITICAL injections throw PolicyViolationException → caught by
                // handleExecutionFailure → intent marked VIOLATED.
                // HIGH-RISK injections log a warning and store risk on intent,
                // then let the policy engine decide via injectionRisk field.
                .flatMap(v -> runInjectionGuard(intent))

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

                // ── QUALITY SCORING ───────────────────────────────────────────
                // Scores the response in the EVALUATING phase.
                // Returns a new immutable ExecutionRecord via withQuality() —
                // the original record is not mutated.
                // If responseText is null (adapter didn't populate it) the scorer
                // returns QualityScore.skipped() and the record passes through unchanged.
                .flatMap(record -> scoreQuality(intent, record))

                // Post-execution policy (receives the scored record)
                .flatMap(record ->
                        policyEngine.evaluatePostExecution(intent, record)
                                .invoke(this::assertPolicyAllowed)
                                .replaceWith(record))

                // ── SATISFIED | VIOLATED (with async drift) ───────────────────
                .flatMap(record -> finalizeIntent(intent, record))

                // Persist final state + remaining events
                .flatMap(v -> persistFinalState(intent));
    }

    // ─── Injection guard ─────────────────────────────────────────────────────

    /**
     * Scan the intent payload for prompt injection patterns.
     * Returns voidItem on clean or high-risk (high-risk only flags the intent).
     * Throws PolicyViolationException on CRITICAL — stops the pipeline and
     * marks the intent VIOLATED via the existing failure handler.
     */
    private Uni<Void> runInjectionGuard(Intent intent) {
        PromptInjectionGuardService.ScanResult scan = injectionGuard.scan(intent);

        if (scan.isCritical()) {
            Log.warnf("[InjectionGuard] CRITICAL injection blocked: intent=%s risk=%.2f matches=%d",
                    intent.getId(), scan.injectionRisk(), scan.matches().size());
            // Reuse PolicyViolationException — caught by the existing failure handler
            // which marks the intent VIOLATED and drains events
            throw new PolicyViolationException(
                    "INJECTION_GUARD",
                    String.format("Prompt injection blocked (risk=%.2f, severity=%s)",
                            scan.injectionRisk(), scan.severity()));
        }

        if (scan.isHighRisk()) {
            Log.warnf("[InjectionGuard] HIGH-RISK injection flagged: intent=%s risk=%.2f — continuing",
                    intent.getId(), scan.injectionRisk());
            // Store on intent so the policy engine can evaluate injectionRisk rules
            intent.flagInjectionRisk(BigDecimal.valueOf(scan.injectionRisk()));
        }

        return Uni.createFrom().voidItem();
    }

    // ─── Quality scoring ──────────────────────────────────────────────────────

    /**
     * Score the quality of the adapter response in the EVALUATING phase.
     * Returns a new ExecutionRecord with quality fields populated via withQuality().
     * The original record is not mutated (ExecutionRecord is immutable).
     *
     * If scoring fails or response text is missing, the original record is
     * returned unchanged — quality scoring is non-blocking for the pipeline.
     */
    private Uni<ExecutionRecord> scoreQuality(Intent intent, ExecutionRecord record) {
        Uni<OutputQualityScorerService.QualityScore> scoreUni = qualityScorer.score(intent, record);
        return scoreUni
                .onFailure().invoke(ex ->
                        Log.warnf("[Quality] Scoring failed for intent=%s — using unscored record: %s",
                                intent.getId(), ex.getClass().getSimpleName()))
                .onFailure().recoverWithItem(
                        OutputQualityScorerService.QualityScore.skipped("Scorer error — fallback to unscored"))
                .map(quality -> {
                    if ("SKIPPED".equals(quality.method()) || "ERROR".equals(quality.method())) {
                        return record;  // pass through unchanged on error
                    }
                    ExecutionRecord scored = record.withQuality(
                            BigDecimal.valueOf(quality.overall()),
                            BigDecimal.valueOf(quality.hallucinationRisk()),
                            quality.hallucinationDetected(),
                            quality.reasoning()
                    );
                    Log.infof("[Quality] intent=%s overall=%.2f hallucination=%.2f flagged=%s method=%s",
                            intent.getId(),
                            quality.overall(),
                            quality.hallucinationRisk(),
                            quality.hallucinationDetected(),
                            quality.method());
                    return scored;
                });
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
     * Drift scoring is now async via DriftEvaluatorService — the stub
     * computeDriftScore() returning BigDecimal.ZERO is replaced.
     * Drift is non-blocking: a failure in drift scoring logs a warning
     * and falls back to 0.0 rather than failing the pipeline.
     */
    private Uni<Void> finalizeIntent(Intent intent, ExecutionRecord executionRecord) {
        if (!executionRecord.isSuccess()) {
            intent.markViolated();
            Log.warnf("Intent VIOLATED: id=%s, reason=%s",
                    intent.getId(), executionRecord.getFailureReason());
            return Uni.createFrom().voidItem();
        }

        Uni<BigDecimal> driftUni = driftEvaluator.computeDriftScore(intent, executionRecord);
        return driftUni
                .onFailure().invoke(ex ->
                        Log.warnf("[Drift] Score failed for intent=%s — using 0.0: %s",
                                intent.getId(), ex.getClass().getSimpleName()))
                .onFailure().recoverWithItem(BigDecimal.ZERO)
                .invoke(driftScore -> {
                    intent.updateDriftScore(driftScore, executionRecord.getExecutionId());
                    intent.markSatisfied();

                    // Pre-compute values to avoid type-inference issues in varargs log call
                    double costVal    = executionRecord.getCost().doubleValue();
                    double driftVal   = driftScore.doubleValue();
                    int    attempts   = executionRecord.getAttemptNumber();
                    String qualityStr = executionRecord.isQualityScored()
                            ? String.format("%.2f", executionRecord.getQualityScore().doubleValue())
                            : "unscored";

                    Log.infof("Intent SATISFIED: id=%s cost=$%.6f drift=%.4f attempts=%d quality=%s",
                            intent.getId(), costVal, driftVal, attempts, qualityStr);
                })
                .replaceWithVoid();
    }

    /**
     * Persist terminal state: intent + remaining domain events + learning + telemetry.
     */
    private Uni<Void> persistFinalState(Intent intent) {
        return intentRepository.save(intent)
                .flatMap(v -> drainEvents(intent))
                .flatMap(v -> learningEngine.updateProfiles(intent.getId()))
                .flatMap(v -> publishTelemetry(intent))
                .replaceWithVoid();
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

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

    private Uni<Void> publishTelemetry(Intent intent) {
        return telemetry.publish(
                intent.getPhase(),
                intent.getTenantId(),
                intent.getId(),
                intent.getVersion());
    }
}
