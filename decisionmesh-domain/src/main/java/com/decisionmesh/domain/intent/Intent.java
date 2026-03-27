package com.decisionmesh.domain.intent;

import com.decisionmesh.domain.event.DomainEvent;
import com.decisionmesh.domain.event.IntentEventType;
import com.decisionmesh.domain.event.IntentStateChangedEvent;
import com.decisionmesh.domain.intent.value.IntentConstraints;
import com.decisionmesh.domain.intent.value.IntentObjective;
import com.decisionmesh.domain.value.Budget;
import com.decisionmesh.domain.value.DriftScore;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class Intent {

    private UUID id;
    private UUID tenantId;
    private UUID userId;

    private final String intentType;
    private final IntentObjective objective;
    private final IntentConstraints constraints;

    private IntentPhase phase;
    private SatisfactionState satisfactionState;
    private int retryCount;
    private int maxRetries;

    private Budget budget;
    private DriftScore driftScore;

    private boolean terminal;
    private long version;

    private final Instant createdAt;
    private Instant updatedAt;

    // =========================================================================
    // Domain events — in-memory only, never persisted as part of Intent JSON.
    // Drained by pullDomainEvents() before the intent is saved.
    // @JsonIgnore on both field and accessors covers all Jackson discovery paths.
    // =========================================================================
    @JsonIgnore
    private final List<DomainEvent> events = new ArrayList<>();

    // =========================================================================
    // FULL CONSTRUCTOR (rehydration from Redis / DB)
    // =========================================================================
    private final boolean rehydrated;
    private Intent(UUID id,
                   UUID tenantId,
                   UUID userId,
                   String intentType,
                   IntentObjective objective,
                   IntentConstraints constraints,
                   IntentPhase phase,
                   SatisfactionState satisfactionState,
                   int retryCount,
                   int maxRetries,
                   Budget budget,
                   DriftScore driftScore,
                   boolean terminal,
                   long version,
                   Instant createdAt,
                   Instant updatedAt,
                   boolean rehydrated) {

        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.intentType = intentType;
        this.objective = objective;
        this.constraints = constraints;
        this.phase = phase;
        this.satisfactionState = satisfactionState;
        this.retryCount = retryCount;
        this.maxRetries = maxRetries;
        this.budget = budget;
        this.driftScore = driftScore;
        this.terminal = terminal;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.rehydrated = rehydrated;

        if (!rehydrated) {
            emit(IntentEventType.CREATED);
        }
    }

    // =========================================================================
    // FACTORY — programmatic creation by orchestrator
    // =========================================================================

    public static Intent create(UUID tenantId,
                                UUID userId,
                                String intentType,
                                IntentObjective objective,
                                IntentConstraints constraints,
                                Budget budget) {

        Objects.requireNonNull(tenantId,    "tenantId is required");
        Objects.requireNonNull(userId,      "userId is required");
        Objects.requireNonNull(intentType,  "intentType is required");
        Objects.requireNonNull(objective,   "objective is required");
        Objects.requireNonNull(constraints, "constraints is required");
        Objects.requireNonNull(budget,      "budget is required");

        return new Intent(
                UUID.randomUUID(),
                tenantId,
                userId,
                intentType,
                objective,
                constraints,
                IntentPhase.CREATED,
                SatisfactionState.UNKNOWN,
                0,
                constraints.maxRetries(),
                budget,
                DriftScore.of(0.0),
                false,
                0L,
                Instant.now(),
                Instant.now(),
                false
        );
    }

    // =========================================================================
    // JACKSON FACTORY — deserializes incoming REST request body.
    // Only client-supplied fields; server fields set later by orchestrator.
    // =========================================================================

    @JsonCreator
    public static Intent fromRequest(
            @JsonProperty("intentType")  String intentType,
            @JsonProperty("objective")   IntentObjective objective,
            @JsonProperty("constraints") IntentConstraints constraints,
            @JsonProperty("budget")      Budget budget) {

        Objects.requireNonNull(intentType,   "intentType is required");
        Objects.requireNonNull(objective,    "objective is required");
        Objects.requireNonNull(constraints,  "constraints is required");
        Objects.requireNonNull(budget,       "budget is required");

        return new Intent(
                UUID.randomUUID(),        // always fresh — client cannot supply id
                null,                     // tenantId — set via setTenantId()
                null,                     // userId   — set via setUserId()
                intentType,
                objective,
                constraints,
                IntentPhase.CREATED,
                SatisfactionState.UNKNOWN,
                0,
                constraints.maxRetries(),
                budget,
                DriftScore.of(0.0),
                false,
                0L,
                Instant.now(),
                Instant.now(),
                false
        );
    }

    // =========================================================================
    // JSON SERIALIZATION — used for Redis cache storage
    // =========================================================================

    public String toJson() {
        return Json.encode(this);
    }

    /**
     * Rehydrate from Redis JSON.
     *
     * DriftScore serializes as a plain double via @JsonValue (e.g. 0.0),
     * NOT as a nested object — read with getDouble(), not getJsonObject().
     *
     * Budget serializes as a nested object — use mapFrom/mapTo as normal.
     */
    public static Intent fromJson(String json) {
        JsonObject obj = new JsonObject(json);

        // ── helpers ─────────────────────────────────────────────

        UUID id = requiredUUID(obj, "id");
        UUID tenantId = safeUUID(obj, "tenantId");
        UUID userId = safeUUID(obj, "userId");

        String intentType = obj.getString("intentType");

        IntentObjective objective = obj.containsKey("objective") && obj.getJsonObject("objective") != null
                ? JsonObject.mapFrom(obj.getJsonObject("objective")).mapTo(IntentObjective.class)
                : null;

        IntentConstraints constraints = obj.containsKey("constraints") && obj.getJsonObject("constraints") != null
                ? JsonObject.mapFrom(obj.getJsonObject("constraints")).mapTo(IntentConstraints.class)
                : null;

        IntentPhase phase = obj.containsKey("phase") && obj.getString("phase") != null
                ? IntentPhase.valueOf(obj.getString("phase"))
                : IntentPhase.CREATED;

        SatisfactionState satisfactionState =
                obj.containsKey("satisfactionState") && obj.getString("satisfactionState") != null
                        ? SatisfactionState.valueOf(obj.getString("satisfactionState"))
                        : SatisfactionState.UNKNOWN;

        Integer retryCount = obj.getInteger("retryCount", 0);
        Integer maxRetries = obj.getInteger("maxRetries", 0);

        Budget budget = obj.containsKey("budget") && obj.getJsonObject("budget") != null
                ? JsonObject.mapFrom(obj.getJsonObject("budget")).mapTo(Budget.class)
                : null;

        Double rawDrift = obj.getDouble("driftScore");
        DriftScore driftScore = DriftScore.of(rawDrift != null ? rawDrift : 0.0);

        Boolean terminal = obj.getBoolean("terminal", false);
        Long version = obj.getLong("version", 0L);

        Instant createdAt = obj.getString("createdAt") != null
                ? Instant.parse(obj.getString("createdAt"))
                : Instant.now();

        Instant updatedAt = obj.getString("updatedAt") != null
                ? Instant.parse(obj.getString("updatedAt"))
                : Instant.now();

        // ── construct ───────────────────────────────────────────

        return new Intent(
                id,
                tenantId,
                userId,
                intentType,
                objective,
                constraints,
                phase,
                satisfactionState,
                retryCount,
                maxRetries,
                budget,
                driftScore,
                terminal,
                version,
                createdAt,
                updatedAt,
                true
        );
    }

    private static UUID safeUUID(JsonObject obj, String field) {
        String value = obj.getString(field);
        if (value == null || value.isBlank()) return null;
        return UUID.fromString(value);
    }

    private static UUID requiredUUID(JsonObject obj, String field) {
        String value = obj.getString(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return UUID.fromString(value);
    }

    // =========================================================================
    // LIFECYCLE TRANSITIONS
    // =========================================================================

    public void startPlanning() {
        // Accepts CREATED (first submission) and RETRY_SCHEDULED (retry loop).
        // DB state machine: both CREATED and RETRY_SCHEDULED → PLANNING are valid.
        if (phase == IntentPhase.RETRY_SCHEDULED) {
            transition(IntentPhase.RETRY_SCHEDULED, IntentPhase.PLANNING, IntentEventType.PLANNING_STARTED);
        } else {
            transition(IntentPhase.CREATED, IntentPhase.PLANNING, IntentEventType.PLANNING_STARTED);
        }
    }

    public void markPlanned() {
        transition(IntentPhase.PLANNING, IntentPhase.PLANNED, IntentEventType.PLANNED);
    }

    public void markExecuting() {
        transition(IntentPhase.PLANNED, IntentPhase.EXECUTING, IntentEventType.EXECUTION_STARTED);
    }

    public void markEvaluating() {
        transition(IntentPhase.EXECUTING, IntentPhase.EVALUATING, IntentEventType.EVALUATION_STARTED);
    }

    /**
     * Called after markEvaluating() — phase is EVALUATING at this point.
     */
    public void markSatisfied() {
        transition(IntentPhase.EVALUATING, IntentPhase.COMPLETED, IntentEventType.SATISFIED);
        this.satisfactionState = SatisfactionState.SATISFIED;
        this.terminal = true;
        enforceInvariant();
    }

    /**
     * Called from:
     *   - handleExecutionFailure() when retries exhausted → phase is EXECUTING
     *   - post-execution policy block             → phase is EVALUATING
     */
    public void markViolated() {
        if (phase == IntentPhase.EXECUTING) {
            transition(IntentPhase.EXECUTING,  IntentPhase.COMPLETED, IntentEventType.VIOLATED);
        } else {
            transition(IntentPhase.EVALUATING, IntentPhase.COMPLETED, IntentEventType.VIOLATED);
        }
        this.satisfactionState = SatisfactionState.VIOLATED;
        this.terminal = true;
        enforceInvariant();
    }

    public void scheduleRetry() {
        if (phase != IntentPhase.EXECUTING)
            throw new IllegalStateException(
                    "Retry only allowed from EXECUTING phase, current: " + phase);

        if (retryCount >= this.maxRetries)
            throw new IllegalStateException(
                    "Max retries exceeded: " + retryCount + "/" + maxRetries);

        retryCount++;
        phase = IntentPhase.RETRY_SCHEDULED;
        version++;
        touch();
        emit(IntentEventType.RETRY_SCHEDULED);
    }

    public void updateDrift(double score) {
        this.driftScore = DriftScore.of(score);
        version++;
        touch();
        emit(IntentEventType.DRIFT_UPDATED);
    }

    public void consumeBudget(double amount) {
        if (terminal)
            throw new IllegalStateException("Cannot consume budget on terminal intent");

        this.budget = this.budget.consume(amount);
        version++;
        touch();
        emit(IntentEventType.BUDGET_CONSUMED);
    }

    // =========================================================================
    // INTERNAL
    // =========================================================================

    private void transition(IntentPhase expected, IntentPhase next, IntentEventType eventType) {
        if (terminal)
            throw new IllegalStateException(
                    "Cannot transition terminal intent: id=" + id);

        if (phase != expected)
            throw new IllegalStateException(String.format(
                    "Invalid transition: expected=%s, actual=%s, next=%s, id=%s",
                    expected, phase, next, id));

        phase = next;
        version++;
        touch();
        emit(eventType);
    }

    public void resumeExecution() {
        if (phase != IntentPhase.RETRY_SCHEDULED) {
            throw new IllegalStateException(
                    "Can only resume from RETRY_SCHEDULED, current: " + phase);
        }

        phase = IntentPhase.EXECUTING;
        version++;
        touch();
        emit(IntentEventType.EXECUTION_STARTED);
    }

    private void enforceInvariant() {
        if (phase == IntentPhase.COMPLETED &&
                satisfactionState == SatisfactionState.UNKNOWN)
            throw new IllegalStateException(
                    "Completed intent must have satisfaction state set: id=" + id);
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private void emit(IntentEventType type) {
        if (this.tenantId == null) return;
        events.add(new IntentStateChangedEvent(
                UUID.randomUUID(),
                this.id,
                this.tenantId,
                this.version,
                type,
                Instant.now()
        ));
    }

    public void setTenantId(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId must not be null");

        boolean firstTime = this.tenantId == null;
        this.tenantId = tenantId;

        if (firstTime && !rehydrated) {
            events.add(new IntentStateChangedEvent(
                    UUID.randomUUID(),
                    this.id,
                    this.tenantId,
                    this.version,
                    IntentEventType.CREATED,
                    Instant.now()
            ));
        }
    }

    // =========================================================================
    // GETTERS
    // =========================================================================

    public UUID getId()                             { return id; }
    public UUID getTenantId()                       { return tenantId; }
    public UUID getUserId()                         { return userId; }
    public String getIntentType()                   { return intentType; }
    public IntentObjective getObjective()           { return objective; }
    public IntentConstraints getConstraints()       { return constraints; }
    public IntentPhase getPhase()                   { return phase; }
    public SatisfactionState getSatisfactionState() { return satisfactionState; }
    public int getRetryCount()                      { return retryCount; }
    public int getMaxRetries()                      { return maxRetries; }
    public Budget getBudget()                       { return budget; }

    /**
     * Convenience accessor — returns the budget ceiling directly.
     * Equivalent to getBudget().getCeilingUsd() but null-safe.
     * Used by LlmExecutionEngine and ExecutionRecordRepository.
     */
    public Double getCeilingUsd() {
        return budget != null ? budget.getCeilingUsd() : null;
    }
    public DriftScore getDriftScore()               { return driftScore; }
    public boolean isTerminal()                     { return terminal; }
    public long getVersion()                        { return version; }
    public Instant getCreatedAt()                   { return createdAt; }
    public Instant getUpdatedAt()                   { return updatedAt; }

    @JsonIgnore
    public List<DomainEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    @JsonIgnore
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    public void updateDriftScore(BigDecimal score, UUID executionId) {
        if (score != null) {
            this.driftScore = DriftScore.of(score.doubleValue());
            version++;
            touch();
        }
    }

    // =========================================================================
    // SETTERS — orchestrator use only, never exposed to clients
    // =========================================================================



    public void setUserId(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
        this.userId = userId;
    }

    public void setId(UUID id) {
        if (id == null) throw new IllegalArgumentException("userId must not be null");
        this.id = id;
    }
}