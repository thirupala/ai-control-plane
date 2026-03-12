package com.decisionmesh.domain.plan;

import com.decisionmesh.domain.value.PlanVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.Json;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Plan {

    private final UUID planId;
    private final UUID intentId;
    private final PlanVersion version;
    private final PlanStrategy strategy;
    private final List<String> orderedAdapters;

    private final double predictedCost;
    private final long predictedLatency;
    private final double objectiveScore;

    private final Instant createdAt;

    private Plan(UUID planId,
                 UUID intentId,
                 PlanVersion version,
                 PlanStrategy strategy,
                 List<String> orderedAdapters,
                 double predictedCost,
                 long predictedLatency,
                 double objectiveScore,
                 Instant createdAt) {

        this.planId = planId;
        this.intentId = intentId;
        this.version = version;
        this.strategy = strategy;
        this.orderedAdapters = List.copyOf(orderedAdapters);
        this.predictedCost = predictedCost;
        this.predictedLatency = predictedLatency;
        this.objectiveScore = objectiveScore;
        this.createdAt = createdAt;
    }

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

    public UUID getPlanId() { return planId; }
    public UUID getIntentId() { return intentId; }
    public PlanVersion getVersion() { return version; }
    public PlanStrategy getStrategy() { return strategy; }
    public List<String> getOrderedAdapters() { return orderedAdapters; }

    public double getPredictedCost() { return predictedCost; }
    public long getPredictedLatency() { return predictedLatency; }
    public double getObjectiveScore() { return objectiveScore; }

    public Instant getCreatedAt() { return createdAt; }
}
