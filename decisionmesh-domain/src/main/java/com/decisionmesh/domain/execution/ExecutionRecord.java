package com.decisionmesh.domain.execution;

import com.decisionmesh.domain.value.PlanVersion;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ExecutionRecord {

    private final UUID executionId;
    private final UUID intentId;
    private final int attemptNumber;
    private final String adapterId;
    private final long latencyMs;
    private final double cost;
    private final FailureType failureType;
    private final PlanVersion planVersion;
    private final Instant timestamp;

    // ✅ Jackson constructor (CRITICAL FIX)
    @JsonCreator
    public ExecutionRecord(
            @JsonAlias("id")
            @JsonProperty("executionId") UUID executionId,
            @JsonProperty("intentId") UUID intentId,
            @JsonProperty("attemptNumber") int attemptNumber,
            @JsonProperty("adapterId") String adapterId,
            @JsonProperty("latencyMs") long latencyMs,
            @JsonProperty("cost") double cost,
            @JsonProperty("failureType") FailureType failureType,
            @JsonProperty("planVersion") PlanVersion planVersion,
            @JsonProperty("timestamp") Instant timestamp
    ) {
        this.executionId = executionId != null ? executionId : UUID.randomUUID();
        this.intentId = intentId;
        this.attemptNumber = attemptNumber;
        this.adapterId = adapterId;
        this.latencyMs = latencyMs;
        this.cost = cost;
        this.failureType = failureType;
        this.planVersion = planVersion;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
    }

    // ✅ Factory for domain usage
    public static ExecutionRecord of(UUID intentId,
                                     int attemptNumber,
                                     String adapterId,
                                     long latencyMs,
                                     double cost,
                                     FailureType failureType,
                                     PlanVersion version) {

        return new ExecutionRecord(
                UUID.randomUUID(),
                intentId,
                attemptNumber,
                adapterId,
                latencyMs,
                cost,
                failureType,
                version,
                Instant.now()
        );
    }

    // ===============================
    // JSON
    // ===============================

    // ✅ SINGLE shared mapper (FIXED)
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public static ExecutionRecord fromJson(String json) {
        try {
            return MAPPER.readValue(json, ExecutionRecord.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize ExecutionRecord", e);
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize ExecutionRecord", e);
        }
    }

    // ===============================
    // Semantic Helpers
    // ===============================

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