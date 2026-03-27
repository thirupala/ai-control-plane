package com.decisionmesh.domain.execution;

import com.decisionmesh.domain.value.PlanVersion;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of a single adapter execution attempt.
 *
 * Serialization: pass the Quarkus-managed ObjectMapper (CDI bean) to
 * toJson() / fromJson() — it has JavaTimeModule registered and
 * WRITE_DATES_AS_TIMESTAMPS disabled.
 *
 * Field rename history:
 *   costUsd → cost  (migration: @JsonAlias handles old Redis records)
 *   id      → executionId  (@JsonAlias("id") on executionId parameter)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ExecutionRecord {

    private final UUID        executionId;
    private final UUID        intentId;
    private final int         attemptNumber;
    private final String      adapterId;
    private final long        latencyMs;
    private final BigDecimal  cost;           // BigDecimal — no float precision loss
    private final FailureType failureType;
    private final PlanVersion planVersion;
    private final Instant     timestamp;

    // ── Constructor ───────────────────────────────────────────────────────────

    @JsonCreator
    public ExecutionRecord(
            @JsonProperty("executionId") @JsonAlias("id")      UUID        executionId,
            @JsonProperty("intentId")                          UUID        intentId,
            @JsonProperty("attemptNumber")                     int         attemptNumber,
            @JsonProperty("adapterId")                         String      adapterId,
            @JsonProperty("latencyMs")                         long        latencyMs,
            @JsonProperty("cost")        @JsonAlias("costUsd") BigDecimal  cost,
            @JsonProperty("failureType")                       FailureType failureType,
            @JsonProperty("planVersion")                       PlanVersion planVersion,
            @JsonProperty("timestamp")                         Instant     timestamp) {

        // Fail loudly — silent defaults corrupt identity and historical ordering
        if (executionId == null) throw new IllegalArgumentException("executionId must not be null");
        if (intentId    == null) throw new IllegalArgumentException("intentId must not be null");
        if (timestamp   == null) throw new IllegalArgumentException("timestamp must not be null");

        this.executionId   = executionId;
        this.intentId      = intentId;
        this.attemptNumber = attemptNumber;
        this.adapterId     = adapterId;
        this.latencyMs     = latencyMs;
        this.cost          = cost != null ? cost : BigDecimal.ZERO;
        this.failureType   = failureType;
        this.planVersion   = planVersion;
        this.timestamp     = timestamp;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static ExecutionRecord of(UUID        intentId,
                                     int         attemptNumber,
                                     String      adapterId,
                                     long        latencyMs,
                                     BigDecimal  cost,
                                     FailureType failureType,
                                     PlanVersion planVersion) {
        return new ExecutionRecord(
                UUID.randomUUID(),
                intentId,
                attemptNumber,
                adapterId,
                latencyMs,
                cost,
                failureType,
                planVersion,
                Instant.now()
        );
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize ExecutionRecord id=" + executionId, e);
        }
    }

    public static ExecutionRecord fromJson(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, ExecutionRecord.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to deserialize ExecutionRecord", e);
        }
    }

    // ── Semantic helpers ──────────────────────────────────────────────────────

    @JsonIgnore
    public boolean isSuccess() {
        return failureType == null;
    }

    @JsonIgnore
    public String getFailureReason() {
        return failureType != null ? failureType.name() : null;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID        getExecutionId()   { return executionId; }
    public UUID        getIntentId()      { return intentId; }
    public int         getAttemptNumber() { return attemptNumber; }
    public String      getAdapterId()     { return adapterId; }
    public long        getLatencyMs()     { return latencyMs; }
    public BigDecimal getCost()          { return cost; }
    public FailureType getFailureType()   { return failureType; }
    public PlanVersion getPlanVersion()   { return planVersion; }
    public Instant     getTimestamp()     { return timestamp; }
}