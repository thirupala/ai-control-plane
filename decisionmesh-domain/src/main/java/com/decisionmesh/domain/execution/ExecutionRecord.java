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
 *
 * Quality fields (added V5 — nullable, all existing records deserialize fine):
 *   responseText          — raw text returned by the adapter (stored for quality evaluation)
 *   qualityScore          — overall output quality 0.0–1.0
 *   hallucinationRisk     — probability of hallucination 0.0–1.0
 *   hallucinationDetected — true if hallucinationRisk >= configured threshold
 *   qualityReasoning      — one-sentence explanation from the judge model
 *
 * Because this class is immutable, quality scores are applied via
 * withQuality() which returns a new instance — no setters.
 * The adapter response text is captured via withResponseText() after
 * the adapter call completes, before EVALUATING phase runs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ExecutionRecord {

    private final UUID        executionId;
    private final UUID        intentId;
    private final int         attemptNumber;
    private final String      adapterId;
    private final long        latencyMs;
    private final BigDecimal  cost;
    private final FailureType failureType;
    private final PlanVersion planVersion;
    private final Instant     timestamp;

    // ── Quality / evaluation fields (nullable — V5 migration) ─────────────────
    private final String     responseText;          // raw adapter output — set after EXECUTING, used in EVALUATING
    private final BigDecimal qualityScore;          // null until EVALUATING phase completes
    private final BigDecimal hallucinationRisk;     // null until EVALUATING phase completes
    private final Boolean    hallucinationDetected; // null until EVALUATING phase completes
    private final String     qualityReasoning;      // null until EVALUATING phase completes

    // ── Constructor ───────────────────────────────────────────────────────────

    @JsonCreator
    public ExecutionRecord(
            @JsonProperty("executionId")        @JsonAlias("id")      UUID        executionId,
            @JsonProperty("intentId")                                  UUID        intentId,
            @JsonProperty("attemptNumber")                             int         attemptNumber,
            @JsonProperty("adapterId")                                 String      adapterId,
            @JsonProperty("latencyMs")                                 long        latencyMs,
            @JsonProperty("cost")               @JsonAlias("costUsd") BigDecimal  cost,
            @JsonProperty("failureType")                               FailureType failureType,
            @JsonProperty("planVersion")                               PlanVersion planVersion,
            @JsonProperty("timestamp")                                 Instant     timestamp,
            @JsonProperty("responseText")                              String      responseText,
            @JsonProperty("qualityScore")                              BigDecimal  qualityScore,
            @JsonProperty("hallucinationRisk")                         BigDecimal  hallucinationRisk,
            @JsonProperty("hallucinationDetected")                     Boolean     hallucinationDetected,
            @JsonProperty("qualityReasoning")                          String      qualityReasoning) {

        if (executionId == null) throw new IllegalArgumentException("executionId must not be null");
        if (intentId    == null) throw new IllegalArgumentException("intentId must not be null");
        if (timestamp   == null) throw new IllegalArgumentException("timestamp must not be null");

        this.executionId          = executionId;
        this.intentId             = intentId;
        this.attemptNumber        = attemptNumber;
        this.adapterId            = adapterId;
        this.latencyMs            = latencyMs;
        this.cost                 = cost != null ? cost : BigDecimal.ZERO;
        this.failureType          = failureType;
        this.planVersion          = planVersion;
        this.timestamp            = timestamp;
        this.responseText         = responseText;
        this.qualityScore         = qualityScore;
        this.hallucinationRisk    = hallucinationRisk;
        this.hallucinationDetected = hallucinationDetected;
        this.qualityReasoning     = qualityReasoning;
    }

    // ── Factory — original (existing callers unchanged) ───────────────────────

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
                Instant.now(),
                null,   // responseText          — set after adapter call via withResponseText()
                null,   // qualityScore          — not yet evaluated
                null,   // hallucinationRisk      — not yet evaluated
                null,   // hallucinationDetected  — not yet evaluated
                null    // qualityReasoning       — not yet evaluated
        );
    }

    // ── Copy-factory — used by orchestrator after EVALUATING phase ────────────

    /**
     * Returns a new ExecutionRecord with quality scores applied.
     * All original fields are preserved unchanged.
     *
     * Usage in ControlPlaneOrchestrator:
     *   ExecutionRecord scored = execution.withQuality(
     *       BigDecimal.valueOf(quality.overall()),
     *       BigDecimal.valueOf(quality.hallucinationRisk()),
     *       quality.hallucinationDetected(),
     *       quality.reasoning()
     *   );
     */
    /**
     * Returns a new ExecutionRecord with the raw adapter response text captured.
     * Called immediately after the adapter call completes (end of EXECUTING phase),
     * before EVALUATING runs — so the quality scorer has the text to analyse.
     */
    public ExecutionRecord withResponseText(String responseText) {
        return new ExecutionRecord(
                this.executionId,
                this.intentId,
                this.attemptNumber,
                this.adapterId,
                this.latencyMs,
                this.cost,
                this.failureType,
                this.planVersion,
                this.timestamp,
                responseText,
                this.qualityScore,
                this.hallucinationRisk,
                this.hallucinationDetected,
                this.qualityReasoning
        );
    }

    /**
     * Returns a new ExecutionRecord with quality scores applied.
     * All original fields (including responseText) are preserved unchanged.
     *
     * Usage in ControlPlaneOrchestrator after EVALUATING phase:
     *   ExecutionRecord scored = execution.withQuality(
     *       BigDecimal.valueOf(quality.overall()),
     *       BigDecimal.valueOf(quality.hallucinationRisk()),
     *       quality.hallucinationDetected(),
     *       quality.reasoning()
     *   );
     */
    public ExecutionRecord withQuality(BigDecimal qualityScore,
                                       BigDecimal hallucinationRisk,
                                       boolean    hallucinationDetected,
                                       String     qualityReasoning) {
        return new ExecutionRecord(
                this.executionId,
                this.intentId,
                this.attemptNumber,
                this.adapterId,
                this.latencyMs,
                this.cost,
                this.failureType,
                this.planVersion,
                this.timestamp,
                this.responseText,   // ← preserved from withResponseText()
                qualityScore,
                hallucinationRisk,
                hallucinationDetected,
                qualityReasoning
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

    @JsonIgnore
    public boolean hasResponseText() {
        return responseText != null && !responseText.isBlank();
    }

    @JsonIgnore
    public boolean isQualityScored() {
        return qualityScore != null;
    }

    @JsonIgnore
    public boolean isHallucinationFlagged() {
        return Boolean.TRUE.equals(hallucinationDetected);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID        getExecutionId()          { return executionId; }
    public UUID        getIntentId()             { return intentId; }
    public int         getAttemptNumber()        { return attemptNumber; }
    public String      getAdapterId()            { return adapterId; }
    public long        getLatencyMs()            { return latencyMs; }
    public BigDecimal  getCost()                 { return cost; }
    public FailureType getFailureType()          { return failureType; }
    public PlanVersion getPlanVersion()          { return planVersion; }
    public Instant     getTimestamp()            { return timestamp; }
    public String      getResponseText()         { return responseText; }
    public BigDecimal  getQualityScore()         { return qualityScore; }
    public BigDecimal  getHallucinationRisk()    { return hallucinationRisk; }
    public Boolean     getHallucinationDetected(){ return hallucinationDetected; }
    public String      getQualityReasoning()     { return qualityReasoning; }
}
