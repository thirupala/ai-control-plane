package com.decisionmesh.domain.plan;

import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.value.PlanVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Plan {

    private final UUID           planId;
    private final UUID           intentId;
    private final PlanVersion    version;
    private final PlanStrategy   strategy;
    private final List<String>   orderedAdapters;  // adapter IDs — original field

    private final double  predictedCost;
    private final long    predictedLatency;
    private final double  objectiveScore;
    private final Instant createdAt;

    // ── Added fields (not serialized — carried in memory only) ───────────────
    // Intent is never persisted here; Plan.fromJson() leaves it null.
    // LlmExecutionEngine attaches it via withIntent().
    private transient Intent     intent;
    private transient List<PlanStep> steps; // built lazily from orderedAdapters

    // ── Original constructor ──────────────────────────────────────────────────

    private Plan(UUID planId,
                 UUID intentId,
                 PlanVersion version,
                 PlanStrategy strategy,
                 List<String> orderedAdapters,
                 double predictedCost,
                 long predictedLatency,
                 double objectiveScore,
                 Instant createdAt) {

        this.planId          = planId;
        this.intentId        = intentId;
        this.version         = version;
        this.strategy        = strategy;
        this.orderedAdapters = List.copyOf(orderedAdapters);
        this.predictedCost   = predictedCost;
        this.predictedLatency = predictedLatency;
        this.objectiveScore  = objectiveScore;
        this.createdAt       = createdAt;
    }

    // ── Original factory (used by IntentCentricPlanner) ───────────────────────

    public static Plan create(UUID intentId,
                              PlanVersion version,
                              PlanStrategy strategy,
                              List<String> adapters,
                              double predictedCost,
                              long predictedLatency,
                              double objectiveScore) {

        return new Plan(
                UUID.randomUUID(),
                intentId,
                version,
                strategy,
                adapters,
                predictedCost,
                predictedLatency,
                objectiveScore,
                Instant.now()
        );
    }

    // ── Methods needed by LlmExecutionEngine ─────────────────────────────────

    /**
     * The primary (first attempt) PlanStep.
     *
     * Built from orderedAdapters[0].  adapterId is the String ID from the planner.
     * configSnapshot is empty — the engine's AdapterRegistry will load the real
     * config from the adapters table and inject it via PlanStep.withAdapter().
     *
     * If the plan has no adapters (cold-start), returns a step with adapterId=null
     * which signals LlmExecutionEngine to use dynamic selection via AdapterRegistry.
     */
    public PlanStep getPrimaryStep() {
        if (orderedAdapters.isEmpty()) {
            return PlanStep.builder()
                    .planId(planId)
                    .intentId(intentId)
                    .stepOrder(0)
                    .stepType("PRIMARY")
                    .configSnapshot(new JsonObject())
                    .build();
        }

        String adapterId = orderedAdapters.get(0);
        return PlanStep.builder()
                .planId(planId)
                .intentId(intentId)
                .adapterId(toUuidOrNull(adapterId))
                .stepOrder(0)
                .stepType("PRIMARY")
                .conditional(false)
                .configSnapshot(new JsonObject().put("adapterId", adapterId))
                .build();
    }

    /**
     * The fallback PlanStep — null when strategy is SINGLE_ADAPTER
     * or when only one adapter is in orderedAdapters.
     *
     * Triggers on TIMEOUT, ADAPTER_ERROR, or RATE_LIMITED.
     */
    public PlanStep getFallbackStep() {
        if (strategy == PlanStrategy.SINGLE_ADAPTER || orderedAdapters.size() < 2) {
            return null;
        }

        String adapterId = orderedAdapters.get(1);
        return PlanStep.builder()
                .planId(planId)
                .intentId(intentId)
                .adapterId(toUuidOrNull(adapterId))
                .stepOrder(1)
                .stepType("FALLBACK")
                .conditional(true)
                .conditionExpr(Map.of(
                        "trigger",       "PREVIOUS_STEP_FAILED",
                        "failure_types", List.of("TIMEOUT", "ADAPTER_ERROR", "RATE_LIMITED")
                ))
                .configSnapshot(new JsonObject().put("adapterId", adapterId))
                .build();
    }

    /**
     * The Intent this plan was created for.
     * Attached in memory by LlmExecutionEngine — null when loaded from JSON/DB.
     * Always non-null during execution (engine calls withIntent() before execute()).
     */
    public Intent getIntent() {
        return intent;
    }

    /**
     * Returns a copy of this Plan with the Intent attached.
     * Called by LlmExecutionEngine before dispatching to an adapter.
     */
    public Plan withIntent(Intent intent) {
        Plan copy = new Plan(planId, intentId, version, strategy, orderedAdapters,
                predictedCost, predictedLatency, objectiveScore, createdAt);
        copy.intent = intent;
        return copy;
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    public String toJson() {
        return Json.encode(this);
    }

    public static Plan fromJson(String json) {
        ObjectMapper MAPPER = new ObjectMapper();
        try {
            return MAPPER.readValue(json, Plan.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Original getters ──────────────────────────────────────────────────────

    public UUID          getPlanId()           { return planId; }
    public UUID          getIntentId()         { return intentId; }
    public PlanVersion   getVersion()          { return version; }
    public PlanStrategy  getStrategy()         { return strategy; }
    public List<String>  getOrderedAdapters()  { return orderedAdapters; }
    public double        getPredictedCost()    { return predictedCost; }
    public long          getPredictedLatency() { return predictedLatency; }
    public double        getObjectiveScore()   { return objectiveScore; }
    public Instant       getCreatedAt()        { return createdAt; }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static UUID toUuidOrNull(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}