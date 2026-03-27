package com.decisionmesh.application.service;

import com.decisionmesh.application.port.AdapterLearningPort;
import com.decisionmesh.application.port.AdapterStats;
import com.decisionmesh.application.port.Planner;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.intent.value.IntentConstraints;
import com.decisionmesh.domain.intent.value.ObjectiveType;
import com.decisionmesh.domain.plan.Plan;
import com.decisionmesh.domain.plan.PlanStep;
import com.decisionmesh.domain.plan.PlanStrategy;
import com.decisionmesh.domain.value.PlanVersion;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Adapter selection planner for the AI Control Plane.
 *
 * Responsibilities:
 *   1. Determine candidate adapters — from constraints.allowedAdapters() if specified,
 *      or ALL active LLM adapters for this tenant + intentType from DB
 *   2. Load live EMA stats for candidates from adapter_performance_profiles
 *   3. Score adapters by ObjectiveType (COST / LATENCY / QUALITY / RISK)
 *   4. Select PRIMARY (best) + FALLBACK (second-best) adapters
 *   5. Build Plan with PlanStep objects ready for LlmExecutionEngine
 *
 * Integration:
 *   - AdapterLearningPort → AdapterLearningPortImpl → adapter_performance_profiles table
 *   - Plan + PlanStep → consumed by LlmExecutionEngine
 *   - ObjectiveType from intent.getObjective().objectiveType() drives scoring weights
 *
 * Cold-start handling:
 *   - When no stats exist, all adapters get equal cold-start priors
 *   - First adapter in candidate list becomes primary (deterministic)
 *   - Epsilon-greedy exploration still applies (config: planner.epsilon)
 */
@ApplicationScoped
public class IntentCentricPlanner implements Planner {

    private static final double EPSILON_DEFAULT      = 0.10;
    private static final double FAILURE_PENALTY      = 10.0;
    private static final double DEGRADED_SCORE       = Double.MAX_VALUE; // always last

    @ConfigProperty(name = "planner.epsilon",          defaultValue = "0.10")
    double epsilon;

    @ConfigProperty(name = "planner.fallback-enabled", defaultValue = "true")
    boolean fallbackEnabled;

    private final AdapterLearningPort learningPort;
    private final Random              random = new Random();

    @Inject
    public IntentCentricPlanner(AdapterLearningPort learningPort) {
        this.learningPort = learningPort;
    }

    // ── Planner port ──────────────────────────────────────────────────────────

    @Override
    public Uni<Plan> plan(Intent intent) {
        IntentConstraints constraints = intent.getConstraints();
        List<String>      allowed     = constraints != null
                ? constraints.allowedAdapters() : List.of();

        // Load stats: specific list if allowedAdapters is set, otherwise all for intentType
        Uni<Map<String, AdapterStats>> statsFuture = (allowed != null && !allowed.isEmpty())
                ? learningPort.getStats(intent.getTenantId(), allowed)
                : learningPort.getStatsForIntentType(intent.getTenantId(), intent.getIntentType());

        return statsFuture
                .onItem().transform(stats -> buildPlan(intent, stats))
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Planner failed: intent=%s, tenant=%s",
                                intent.getId(), intent.getTenantId()))
                .onFailure().recoverWithItem(buildColdStartPlan(intent));
    }

    // ── Plan construction ─────────────────────────────────────────────────────

    private Plan buildPlan(Intent intent, Map<String, AdapterStats> stats) {
        if (stats == null || stats.isEmpty()) {
            Log.warnf("No adapter stats — cold-start plan: intent=%s", intent.getId());
            return buildColdStartPlan(intent);
        }

        ObjectiveType objective  = resolveObjectiveType(intent);
        boolean       explore    = random.nextDouble() < epsilon;

        // Sort by score ascending (lower = better in our scoring function)
        List<AdapterStats> ranked = stats.values().stream()
                .filter(a -> !a.isDegraded())              // hard-filter degraded if enough data
                .sorted(Comparator.comparingDouble(a -> score(objective, a)))
                .collect(Collectors.toList());

        // If all adapters are degraded, fall back to including them (degraded > no adapter)
        if (ranked.isEmpty()) {
            Log.warnf("All adapters degraded — using degraded adapters as last resort: intent=%s",
                    intent.getId());
            ranked = stats.values().stream()
                    .sorted(Comparator.comparingDouble(a -> score(objective, a)))
                    .collect(Collectors.toList());
        }

        AdapterStats primary;
        boolean      wasExploration = false;

        if (explore && ranked.size() > 1) {
            // Epsilon-greedy: pick a random non-best adapter as primary
            int idx = 1 + random.nextInt(ranked.size() - 1);
            primary       = ranked.get(idx);
            wasExploration = true;
            Log.infof("Planner exploration: selected rank-%d adapter. intent=%s, epsilon=%.2f",
                    idx + 1, intent.getId(), epsilon);
        } else {
            primary = ranked.get(0);
        }

        // Fallback: best adapter that is NOT the primary
        AdapterStats fallback = ranked.stream()
                .filter(a -> !a.adapterId().equals(primary.adapterId()))
                .findFirst()
                .orElse(null);

        List<PlanStep> steps    = new ArrayList<>();
        UUID           planId   = UUID.randomUUID();

        // PRIMARY step
        steps.add(buildPlanStep(planId, intent, primary, 0, "PRIMARY", false, null));

        // FALLBACK step (when enabled and a second adapter exists)
        if (fallbackEnabled && fallback != null) {
            steps.add(buildFallbackStep(planId, intent, fallback));
        }

        String strategy = (fallbackEnabled && fallback != null)
                ? "RANKED_FALLBACK" : "SINGLE_ADAPTER";

        String notes = buildPlannerNotes(objective, primary, fallback, wasExploration);

        Log.infof("Plan built: intent=%s, strategy=%s, primary=%s(%s), fallback=%s, exploration=%b",
                intent.getId(), strategy,
                primary.provider(), primary.model(),
                fallback != null ? fallback.provider() + "(" + fallback.model() + ")" : "none",
                wasExploration);

        // Build ordered adapter ID list for Plan.create() (primary first, fallback second)
        List<String> adapterIds = new java.util.ArrayList<>();
        adapterIds.add(primary.adapterId().toString());
        if (fallbackEnabled && fallback != null) {
            adapterIds.add(fallback.adapterId().toString());
        }

        PlanStrategy planStrategy = (fallbackEnabled && fallback != null)
                ? PlanStrategy.RANKED_FALLBACK : PlanStrategy.SINGLE_ADAPTER;

        return Plan.create(
                intent.getId(),
                PlanVersion.initial(),
                planStrategy,
                adapterIds,
                primary.avgCost(),
                (long) primary.avgLatency(),
                primary.compositeScore()
        ).withIntent(intent);
    }

    /**
     * Cold-start plan — no stats available yet.
     * Creates a plan with the first known adapter and no fallback.
     * The LlmExecutionEngine will perform dynamic selection via AdapterRegistry.
     */
    private Plan buildColdStartPlan(Intent intent) {

        Log.infof("Cold-start plan: assigning default adapter for intent=%s",
                intent.getId());

        return Plan.create(
                intent.getId(),
                PlanVersion.initial(),
                PlanStrategy.SINGLE_ADAPTER,
                List.of("openai"),   //   FIX
                0.0,
                0L,
                0.5
        ).withIntent(intent);
    }

    // ── PlanStep builders ─────────────────────────────────────────────────────

    private PlanStep buildPlanStep(UUID planId, Intent intent, AdapterStats adapter,
                                   int order, String stepType,
                                   boolean conditional, Map<String, Object> conditionExpr) {
        JsonObject config = buildConfigSnapshot(adapter, intent);

        return PlanStep.builder()
                .planId(planId)
                .intentId(intent.getId())
                .tenantId(intent.getTenantId())
                .adapterId(adapter.adapterId())
                .stepOrder(order)
                .stepType(stepType)
                .conditional(conditional)
                .conditionExpr(conditionExpr)
                .configSnapshot(config)
                .provider(adapter.provider())
                .model(adapter.model())
                .region(adapter.region())
                .build();
    }

    private PlanStep buildFallbackStep(UUID planId, Intent intent, AdapterStats adapter) {
        JsonObject config = buildConfigSnapshot(adapter, intent);

        return PlanStep.builder()
                .planId(planId)
                .intentId(intent.getId())
                .tenantId(intent.getTenantId())
                .adapterId(adapter.adapterId())
                .stepOrder(1)
                .stepType("FALLBACK")
                .conditional(true)
                .conditionExpr(Map.of(
                        "trigger",       "PREVIOUS_STEP_FAILED",
                        "failure_types", List.of("TIMEOUT", "ADAPTER_ERROR", "RATE_LIMITED")
                ))
                .configSnapshot(config)
                .provider(adapter.provider())
                .model(adapter.model())
                .region(adapter.region())
                .build();
    }

    /**
     * Build configSnapshot JsonObject for a PlanStep.
     * LlmAdapter.execute() reads model, max_tokens, timeout_ms, temperature from this.
     */
    private JsonObject buildConfigSnapshot(AdapterStats adapter, Intent intent) {
        int  maxTokens = resolveMaxTokens(intent);
        long timeoutMs = resolveTimeoutMs(intent);

        JsonObject config = new JsonObject()
                .put("provider",    adapter.provider())
                .put("model",       adapter.model())
                .put("max_tokens",  maxTokens)
                .put("timeout_ms",  timeoutMs)
                .put("temperature", resolveTemperature(intent));

        // Azure needs resource_name and deployment_name
        if ("AZURE_OPENAI".equalsIgnoreCase(adapter.provider())) {
            config.put("deployment_name", adapter.model());
            // resource_name comes from adapter.config in DB — engine will read it
        }

        return config;
    }

    // ── Scoring (ObjectiveType-aware) ─────────────────────────────────────────

    /**
     * Score adapter for a given objective — lower is better.
     *
     * COST    → minimize cost per call (+ failure penalty)
     * LATENCY → minimize response time (+ failure penalty)
     * QUALITY → minimize failure rate only (cost/latency irrelevant)
     * RISK    → minimize risk score + failure rate (safety-critical intents)
     */
    private double score(ObjectiveType objective, AdapterStats stats) {
        if (stats.isDegraded()) return DEGRADED_SCORE;

        double failurePenalty = stats.failureRate() * FAILURE_PENALTY;
        double costPenalty    = stats.avgCost();
        double latencyPenalty = stats.avgLatency() / 1000.0;    // normalise to seconds
        double riskPenalty    = stats.riskScore();

        return switch (objective) {
            case COST    -> costPenalty    + failurePenalty;
            case LATENCY -> latencyPenalty + failurePenalty;
            case QUALITY -> failurePenalty + (riskPenalty * 0.5);
            case RISK    -> (failurePenalty * 2) + (riskPenalty * 3);
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ObjectiveType resolveObjectiveType(Intent intent) {
        if (intent.getObjective() != null) {
            try {
                return intent.getObjective().objectiveType();
            } catch (Exception ignored) {}
        }
        return ObjectiveType.QUALITY; // safe default
    }

    private int resolveMaxTokens(Intent intent) {
        if (intent.getConstraints() != null && intent.getConstraints().maxTokens() > 0) {
            return intent.getConstraints().maxTokens();
        }
        return 1024; // default
    }

    private long resolveTimeoutMs(Intent intent) {
        if (intent.getConstraints() != null && intent.getConstraints().timeoutSeconds() > 0) {
            return intent.getConstraints().timeoutSeconds() * 1000L;
        }
        return 30_000L; // 30s default
    }

    private double resolveTemperature(Intent intent) {
        ObjectiveType obj = resolveObjectiveType(intent);
        return switch (obj) {
            case QUALITY, RISK -> 0.0;   // deterministic for quality/safety
            case COST          -> 0.2;   // slightly creative but predictable
            case LATENCY       -> 0.1;   // low temp = faster token generation
        };
    }

    private String buildPlannerNotes(ObjectiveType objective,
                                     AdapterStats primary, AdapterStats fallback,
                                     boolean wasExploration) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Objective: %s. ", objective));
        sb.append(String.format("Primary: %s(%s) score=%.4f. ",
                primary.provider(), primary.model(),
                score(objective, primary)));
        if (fallback != null) {
            sb.append(String.format("Fallback: %s(%s) score=%.4f. ",
                    fallback.provider(), fallback.model(),
                    score(objective, fallback)));
        }
        if (wasExploration) {
            sb.append(String.format("Epsilon-greedy exploration (ε=%.2f).", epsilon));
        }
        return sb.toString();
    }
}