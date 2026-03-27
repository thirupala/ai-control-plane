package com.decisionmesh.infrastructure.llm;

import com.decisionmesh.application.port.ExecutionEngine;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.plan.Plan;
import com.decisionmesh.domain.plan.PlanStep;
import com.decisionmesh.domain.value.Budget;
import com.decisionmesh.infrastructure.llm.learning.AdapterPerformanceProfileRepository;
import com.decisionmesh.infrastructure.llm.persistence.ExecutionRecordRepository;
import com.decisionmesh.infrastructure.llm.registry.AdapterRegistry;
import com.decisionmesh.infrastructure.llm.selector.AdapterStats;
import com.decisionmesh.infrastructure.llm.selector.LlmModelSelector;
import com.decisionmesh.infrastructure.llm.selector.SelectedAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class LlmExecutionEngine implements ExecutionEngine {

    private static final double BUDGET_LOW_WARNING_PCT = 0.10; // warn when < 10% remains

    @ConfigProperty(name = "llm.registry.fail-fast-on-empty", defaultValue = "false")
    boolean failFastOnEmptyRegistry;

    private final Map<String, LlmAdapter>            adaptersByProvider;
    private final AdapterRegistry                    adapterRegistry;
    private final LlmModelSelector                   modelSelector;
    private final AdapterPerformanceProfileRepository profileRepository;
    private final ExecutionRecordRepository          executionRecordRepository;
    private final MeterRegistry                      meterRegistry;

    @Inject
    public LlmExecutionEngine(Instance<LlmAdapter> adapters,
                               AdapterRegistry adapterRegistry,
                               LlmModelSelector modelSelector,
                               AdapterPerformanceProfileRepository profileRepository,
                               ExecutionRecordRepository executionRecordRepository,
                               MeterRegistry meterRegistry) {
        this.adaptersByProvider      = StreamSupport
                .stream(adapters.spliterator(), false)
                .collect(Collectors.toMap(a -> a.provider().toUpperCase(), Function.identity()));
        this.adapterRegistry         = adapterRegistry;
        this.modelSelector           = modelSelector;
        this.profileRepository       = profileRepository;
        this.executionRecordRepository = executionRecordRepository;
        this.meterRegistry           = meterRegistry;
        Log.infof("LlmExecutionEngine ready. Providers: %s", adaptersByProvider.keySet());
    }

    @Override
    public Uni<ExecutionRecord> execute(Plan plan, int attemptNumber) {
        Intent   intent      = plan.getIntent();
        PlanStep primaryStep = plan.getPrimaryStep();

        if (primaryStep.getAdapterId() != null) {
            return executeWithFallback(intent, plan, attemptNumber);
        }

        return adapterRegistry.loadCandidates(intent)
                .flatMap(candidates -> {
                    if (candidates.isEmpty()) {
                        Log.errorf("No adapter candidates: tenant=%s, type=%s",
                                intent.getTenantId(), intent.getIntentType());
                        meterRegistry.counter("llm.registry.empty",
                                "tenant", safeId(intent.getTenantId())).increment();
                        if (failFastOnEmptyRegistry) {
                            return Uni.createFrom().failure(new IllegalStateException(
                                    "No active LLM adapters for tenant=" + intent.getTenantId()));
                        }
                        return executeWithFallback(intent, plan, attemptNumber);
                    }
                    return selectAndExecute(intent, plan, candidates, attemptNumber);
                });
    }

    // ── Dynamic selection ────────────────────────────────────────────────────

    private Uni<ExecutionRecord> selectAndExecute(Intent intent, Plan plan,
                                                   List<AdapterStats> candidates,
                                                   int attemptNumber) {
        List<SelectedAdapter> ranked = modelSelector.select(intent, candidates);
        if (ranked.isEmpty()) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "No adapters after selection filtering: intent=" + intent.getId()));
        }

        SelectedAdapter primary      = ranked.get(0);
        PlanStep        enrichedStep = plan.getPrimaryStep()
                .withAdapter(primary.adapterId(), primary.provider(), primary.model());

        Log.infof("Adapter selected: provider=%s, model=%s, score=%.3f, intent=%s",
                primary.provider(), primary.model(), primary.compositeScore(), intent.getId());

        return executeStep(intent, enrichedStep, attemptNumber)
                .call(record -> persistResult(record, intent))
                .call(record -> updateProfile(intent, primary, record))
                .onFailure().recoverWithUni(failure -> {
                    fireAndForgetFailureProfile(intent, primary, failure);
                    return tryFallbacks(intent, plan, ranked, 1, attemptNumber, failure);
                });
    }

    // ── Pre-selected plan path ────────────────────────────────────────────────

    private Uni<ExecutionRecord> executeWithFallback(Intent intent, Plan plan, int attemptNumber) {
        PlanStep primaryStep = plan.getPrimaryStep();

        return executeStep(intent, primaryStep, attemptNumber)
                .call(record -> persistResult(record, intent))
                .call(record -> persistProfileFromStep(intent, primaryStep, record))
                .onFailure().recoverWithUni(failure -> {
                    fireAndForgetFailureFromStep(intent, primaryStep, failure);
                    PlanStep fallback = plan.getFallbackStep();
                    if (fallback == null || !isFallbackTriggered(fallback, failure)) {
                        return Uni.createFrom().failure(failure);
                    }
                    meterRegistry.counter("llm.fallback.triggered",
                            "tenant", safeId(intent.getTenantId())).increment();
                    return executeStep(intent, fallback, attemptNumber)
                            .call(record -> persistResult(record, intent))
                            .call(record -> persistProfileFromStep(intent, fallback, record))
                            .onFailure().invoke(ex -> fireAndForgetFailureFromStep(intent, fallback, ex));
                });
    }

    // ── Ranked fallback chain ────────────────────────────────────────────────

    private Uni<ExecutionRecord> tryFallbacks(Intent intent, Plan plan,
                                               List<SelectedAdapter> ranked, int idx,
                                               int attemptNumber, Throwable lastFailure) {
        if (idx >= ranked.size()) {
            meterRegistry.counter("llm.adapters.exhausted",
                    "tenant", safeId(intent.getTenantId())).increment();
            return Uni.createFrom().failure(lastFailure);
        }
        SelectedAdapter fallback     = ranked.get(idx);
        PlanStep        fallbackStep = plan.getPrimaryStep()
                .withAdapter(fallback.adapterId(), fallback.provider(), fallback.model());

        return executeStep(intent, fallbackStep, attemptNumber)
                .call(record -> persistResult(record, intent))
                .call(record -> updateProfile(intent, fallback, record))
                .onFailure().recoverWithUni(ex -> {
                    fireAndForgetFailureProfile(intent, fallback, ex);
                    return tryFallbacks(intent, plan, ranked, idx + 1, attemptNumber, ex);
                });
    }

    // ── Step execution ────────────────────────────────────────────────────────

    private Uni<ExecutionRecord> executeStep(Intent intent, PlanStep step, int attempt) {
        String     provider  = step.getProvider() != null ? step.getProvider().toUpperCase() : "";
        Instant    startedAt = Instant.now();
        LlmAdapter adapter   = adaptersByProvider.get(provider);

        if (adapter == null) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "No adapter for provider: " + provider + ". Available: " + adaptersByProvider.keySet()));
        }

        // Budget guard before HTTP call
        return checkBudget(intent)
                .flatMap(v -> adapter.execute(intent, step, attempt))
                // Retry RATE_LIMITED / ADAPTER_ERROR with backoff — not TIMEOUT / POLICY_BLOCK
                .onFailure(this::isRetryable)
                .retry()
                .withBackOff(Duration.ofMillis(200), Duration.ofSeconds(2))
                .atMost(2)
                .invoke(record -> recordStepMetric(provider, "SUCCESS", startedAt))
                .onFailure().invoke(ex -> recordStepMetric(provider, classifyFailure(ex), startedAt));
    }

    // ── Budget check ─────────────────────────────────────────────────────────

    private Uni<Void> checkBudget(Intent intent) {
        if (intent.getBudget() == null) return Uni.createFrom().voidItem();

        Budget budget = intent.getBudget();
        if (!budget.isConstrained()) return Uni.createFrom().voidItem();

        if (budget.isExceeded()) {
            meterRegistry.counter("llm.budget.exhausted",
                    "tenant", safeId(intent.getTenantId())).increment();
            return Uni.createFrom().failure(
                    new IllegalStateException("Budget exhausted: intent=" + intent.getId()
                            + ", ceiling=" + budget.getCeilingUsd()
                            + ", spent=" + budget.getSpentUsd()));
        }

        // Warn when < 10% remaining
        double remaining = budget.remaining();
        if (remaining / budget.getCeilingUsd() < BUDGET_LOW_WARNING_PCT) {
            Log.warnf("Budget low: intent=%s, remaining=%.6f (<%.0f%%)",
                    intent.getId(), remaining, BUDGET_LOW_WARNING_PCT * 100);
            meterRegistry.counter("llm.budget.low_warning",
                    "tenant", safeId(intent.getTenantId())).increment();
        }

        return Uni.createFrom().voidItem();
    }

    // ── Observability ────────────────────────────────────────────────────────

    private void recordStepMetric(String provider, String outcome, Instant startedAt) {
        Duration elapsed = Duration.between(startedAt, Instant.now());
        Timer.builder("llm.step.latency")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(elapsed);
        meterRegistry.counter("llm.step.outcome",
                "provider", provider, "outcome", outcome).increment();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /** Persist execution_record. Uses only actual ExecutionRecord methods. */
    private Uni<Void> persistResult(ExecutionRecord record, Intent intent) {
        return executionRecordRepository.save(record, intent)
                .replaceWithVoid()
                .onFailure().invoke(ex ->
                        Log.warnf(ex, "Non-fatal: persist failed: intent=%s", intent.getId()))
                .onFailure().recoverWithNull().replaceWithVoid();
    }

    private Uni<Void> updateProfile(Intent intent, SelectedAdapter adapter, ExecutionRecord record) {
        boolean success  = record.isSuccess();                        // actual method
        long    latency  = record.getLatencyMs();                     // actual method
        double  cost     = record.getCostUsd() != null               // actual method
                ? record.getCostUsd().doubleValue() : record.getCost();
        double  risk     = 0.0;                                       // not on ExecutionRecord

        return profileUni(success, intent.getTenantId(), adapter.adapterId(),
                adapter.provider(), adapter.model(), adapter.region(),
                latency, cost, risk, record.getId(), intent.getId());
    }

    private Uni<Void> persistProfileFromStep(Intent intent, PlanStep step, ExecutionRecord record) {
        boolean success = record.isSuccess();
        long    latency = record.getLatencyMs();
        double  cost    = record.getCostUsd() != null
                ? record.getCostUsd().doubleValue() : record.getCost();

        return profileUni(success, intent.getTenantId(), step.getAdapterId(),
                step.getProvider(), step.getModel(), step.getRegion(),
                latency, cost, 0.0, record.getId(), intent.getId());
    }

    private Uni<Void> profileUni(boolean success, UUID tenantId, UUID adapterId,
                                  String provider, String model, String region,
                                  long latency, double cost, double risk,
                                  UUID executionId, UUID intentId) {
        Uni<Void> u = success
                ? profileRepository.recordSuccess(tenantId, adapterId, provider, model, region,
                        latency, cost, risk, executionId, intentId)
                : profileRepository.recordFailure(tenantId, adapterId, provider, model, region,
                        latency, cost, executionId, intentId);
        return u.onFailure().recoverWithNull().replaceWithVoid();
    }

    private void fireAndForgetFailureProfile(Intent intent, SelectedAdapter adapter, Throwable ex) {
        long latency = ex instanceof LlmAdapterException lae ? lae.getLatencyMs() : 0L;
        profileRepository.recordFailure(intent.getTenantId(), adapter.adapterId(),
                adapter.provider(), adapter.model(), adapter.region(),
                latency, 0.0, null, intent.getId())
                .subscribe().with(v -> {}, err -> {});
    }

    private void fireAndForgetFailureFromStep(Intent intent, PlanStep step, Throwable ex) {
        long latency = ex instanceof LlmAdapterException lae ? lae.getLatencyMs() : 0L;
        profileRepository.recordFailure(intent.getTenantId(), step.getAdapterId(),
                step.getProvider(), step.getModel(), step.getRegion(),
                latency, 0.0, null, intent.getId())
                .subscribe().with(v -> {}, err -> {});
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isRetryable(Throwable ex) {
        if (!(ex instanceof LlmAdapterException lae)) return false;
        return "RATE_LIMITED".equals(lae.getFailureType()) || "ADAPTER_ERROR".equals(lae.getFailureType());
    }

    private boolean isFallbackTriggered(PlanStep fallback, Throwable failure) {
        if (!fallback.isConditional()) return true;
        Map<String, Object> cond = fallback.getConditionExpr();
        if (cond == null) return true;
        if (!"PREVIOUS_STEP_FAILED".equals(cond.get("trigger"))) return false;
        Object types = cond.get("failure_types");
        if (!(types instanceof Iterable<?> list)) return true;
        String code = failure instanceof LlmAdapterException lae ? lae.getFailureType() : "ADAPTER_ERROR";
        for (Object t : list) { if (code.equals(String.valueOf(t))) return true; }
        return false;
    }

    private String classifyFailure(Throwable ex) {
        if (ex instanceof LlmAdapterException lae) return lae.getFailureType();
        String name = ex.getClass().getSimpleName();
        if (name.contains("Timeout")) return "TIMEOUT";
        return "ADAPTER_ERROR";
    }

    private String safeId(UUID id) { return id != null ? id.toString() : "unknown"; }
}
