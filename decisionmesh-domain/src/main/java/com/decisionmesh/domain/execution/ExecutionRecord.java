package com.decisionmesh.domain.execution;

import com.decisionmesh.domain.value.PlanVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ExecutionRecord {

    private final UUID executionId;
    private final UUID intentId;
    private final int attemptNumber;
    private final String adapterId;
    private final long latencyMs;
    private final double cost; // internal primitive
    private final FailureType failureType;
    private final PlanVersion planVersion;
    private final Instant timestamp;

    public ExecutionRecord(UUID intentId,
                           int attemptNumber,
                           String adapterId,
                           long latencyMs,
                           double cost,
                           FailureType failureType,
                           PlanVersion planVersion) {

        this.executionId = UUID.randomUUID();
        this.intentId = intentId;
        this.attemptNumber = attemptNumber;
        this.adapterId = adapterId;
        this.latencyMs = latencyMs;
        this.cost = cost;
        this.failureType = failureType;
        this.planVersion = planVersion;
        this.timestamp = Instant.now();
    }

    public static ExecutionRecord of(UUID intentId,
                                     int attemptNumber,
                                     String adapterId,
                                     long latencyMs,
                                     double cost,
                                     FailureType failureType,
                                     PlanVersion version) {

        return new ExecutionRecord(
                intentId,
                attemptNumber,
                adapterId,
                latencyMs,
                cost,
                failureType,
                version
        );
    }

    // ===============================
    // Semantic Helpers (IMPORTANT)
    // ===============================

    public static ExecutionRecord fromJson(String json) {
        try {
            return MAPPER.readValue(json, ExecutionRecord.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize ExecutionRecord", e);
        }
    }


    public boolean isSuccess() {
        return failureType == null;
    }

    public BigDecimal getCostUsd() {
        return BigDecimal.valueOf(cost);
    }

    public String getFailureReason() {
        return failureType != null ? failureType.name() : null;
    }

    public UUID getId() {
        return executionId;
    }

    // ===============================
    // JSON
    // ===============================

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ExecutionRecord", e);
        }
    }

    // ===============================
    // Getters
    // ===============================

    public UUID getExecutionId() { return executionId; }
    public UUID getIntentId() { return intentId; }
    public int getAttemptNumber() { return attemptNumber; }
    public String getAdapterId() { return adapterId; }
    public long getLatencyMs() { return latencyMs; }
    public double getCost() { return cost; }
    public FailureType getFailureType() { return failureType; }
    public PlanVersion getPlanVersion() { return planVersion; }
    public Instant getTimestamp() { return timestamp; }
}
