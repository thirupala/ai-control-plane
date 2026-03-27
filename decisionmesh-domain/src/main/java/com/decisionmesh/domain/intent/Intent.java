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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class Intent {

    // ── Shared Jackson mapper — Instant support via JavaTimeModule ────────────
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private UUID id;
    private UUID tenantId;
    private UUID userId;

    private final String             intentType;
    private final IntentObjective    objective;
    private final IntentConstraints  constraints;

    private IntentPhase      phase;
    private SatisfactionState satisfactionState;
    private int              retryCount;
    private int              maxRetries;

    private Budget     budget;
    private DriftScore driftScore;

    private boolean terminal;
    private long    version;

    private final Instant createdAt;
    private       Instant updatedAt;

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

        this.id                = id;
        this.tenantId          = tenantId;
        this.userId            = userId;
        this.intentType        = intentType;
        this.objective         = objective;
        this.constraints       = constraints;
        this.phase             = phase;
        this.satisfactionState = satisfactionState;
        this.retryCount        = retryCount;
        this.maxRetries        = maxRetries;
        this.budget            = budget;
        this.driftScore        = driftScore;
        this.terminal          = terminal;
        this.version           = version;
        this.createdAt         = createdAt;
        this.updatedAt         = updatedAt;
        this.rehydrated        = rehydrated;

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

        Objects.requireNonNull(intentType,  "intentType is required");
        Objects.requireNonNull(objective,   "objective is required");
        Objects.requireNonNull(constraints, "constraints is required");
        Objects.requireNonNull(budget,      "budget is required");

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

    /**
     * Serialises this Intent to JSON for Redis storage.
     * Replaces io.vertx.core.json.Json.encode() — identical output, no Vert.x dep.
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise Intent to JSON", e);
        }
    }

    /**
     * Rehydrates an Intent from a Redis JSON string.
     *
     * DriftScore serializes as a plain double via @JsonValue (e.g. 0.0),
     * NOT as a nested object — read the raw double, not a nested node.
     *
     * Budget serializes as a nested object — deserialized via MAPPER.treeToValue().
     *
     * Replaces the Vert.x JsonObject-based implementation entirely.
     */
    public static Intent fromJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);

            UUID id       = requiredUUID(root, "id");
            UUID tenantId = safeUUID(root, "tenantId");
            UUID userId   = safeUUID(root, "userId");

            String intentType = safeText(root, "intentType");

            IntentObjective objective = root.hasNonNull("objective")
                    ? MAPPER.treeToValue(root.get("objective"), IntentObjective.class)
                    : null;

            IntentConstraints constraints = root.hasNonNull("constraints")
                    ? MAPPER.treeToValue(root.get("constraints"), IntentConstraints.class)
                    : null;

            IntentPhase phase = root.hasNonNull("phase")
                    ? IntentPhase.valueOf(root.get("phase").asText())
                    : IntentPhase.CREATED;

            SatisfactionState satisfactionState = root.hasNonNull("satisfactionState")
                    ? SatisfactionState.valueOf(root.get("satisfactionState").asText())
                    : SatisfactionState.UNKNOWN;

            int retryCount = root.hasNonNull("retryCount") ? root.get("retryCount").asInt(0)  : 0;
            int maxRetries = root.hasNonNull("maxRetries") ? root.get("maxRetries").asInt(0)  : 0;

            Budget budget = root.hasNonNull("budget")
                    ? MAPPER.treeToValue(root.get("budget"), Budget.class)
                    : null;

            // DriftScore: serialized as a plain double via @JsonValue
            double rawDrift = root.hasNonNull("driftScore")
                    ? root.get("driftScore").asDouble(0.0)
                    : 0.0;
            DriftScore driftScore = DriftScore.of(rawDrift);

            boolean terminal = root.hasNonNull("terminal") && root.get("terminal").asBoolean(false);
            long    version  = root.hasNonNull("version")  ? root.get("version").asLong(0L) : 0L;

            Instant createdAt = root.hasNonNull("createdAt") ? parseTimestamp(root,"createdAt") : Instant.now();

            Instant updatedAt = root.hasNonNull("updatedAt") ? parseTimestamp(root,"updatedAt") : Instant.now();

            return new Intent(
                    id, tenantId, userId,
                    intentType, objective, constraints,
                    phase, satisfactionState,
                    retryCount, maxRetries,
                    budget, driftScore,
                    terminal, version,
                    createdAt, updatedAt,
                    true   // rehydrated = true — suppresses CREATED event
            );

        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialise Intent from JSON", e);
        }
    }
    private static Instant parseTimestamp(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.isNumber()) {
            return Instant.ofEpochSecond((long) value.doubleValue());
        } else {
            return Instant.parse(value.asText());
        }
    }

    // ── JSON helpers (replaces JsonObject.getString / getUUID) ───────────────

    private static UUID safeUUID(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        return UUID.fromString(node.asText());
    }

    private static UUID requiredUUID(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank())
            throw new IllegalArgumentException(field + " is required");
        return UUID.fromString(node.asText());
    }

    private static String safeText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return (node == null || node.isNull()) ? null : node.asText();
    }

    // =========================================================================
    // LIFECYCLE TRANSITIONS
    // =========================================================================

    public void startPlanning() {
        if (phase == IntentPhase.RETRY_SCHEDULED) {
            transition(IntentPhase.RETRY_SCHEDULED, IntentPhase.PLANNING, IntentEventType.PLANNING_STARTED);
        } else {
            transition(IntentPhase.CREATED, IntentPhase.PLANNING, IntentEventType.PLANNING_STARTED);
        }
    }

    public void markPlanned()     { transition(IntentPhase.PLANNING,   IntentPhase.PLANNED,    IntentEventType.PLANNED); }
    public void markExecuting()   { transition(IntentPhase.PLANNED,    IntentPhase.EXECUTING,  IntentEventType.EXECUTION_STARTED); }
    public void markEvaluating()  { transition(IntentPhase.EXECUTING,  IntentPhase.EVALUATING, IntentEventType.EVALUATION_STARTED); }

    public void markSatisfied() {
        transition(IntentPhase.EVALUATING, IntentPhase.COMPLETED, IntentEventType.SATISFIED);
        this.satisfactionState = SatisfactionState.SATISFIED;
        this.terminal = true;
        enforceInvariant();
    }

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

    public void resumeExecution() {
        if (phase != IntentPhase.RETRY_SCHEDULED)
            throw new IllegalStateException(
                    "Can only resume from RETRY_SCHEDULED, current: " + phase);
        phase = IntentPhase.EXECUTING;
        version++;
        touch();
        emit(IntentEventType.EXECUTION_STARTED);
    }

    // =========================================================================
    // INTERNAL
    // =========================================================================

    private void transition(IntentPhase expected, IntentPhase next, IntentEventType eventType) {
        if (terminal)
            throw new IllegalStateException("Cannot transition terminal intent: id=" + id);
        if (phase != expected)
            throw new IllegalStateException(String.format(
                    "Invalid transition: expected=%s, actual=%s, next=%s, id=%s",
                    expected, phase, next, id));
        phase = next;
        version++;
        touch();
        emit(eventType);
    }

    private void enforceInvariant() {
        if (phase == IntentPhase.COMPLETED && satisfactionState == SatisfactionState.UNKNOWN)
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

    // =========================================================================
    // SETTERS — orchestrator use only, never exposed to clients
    // =========================================================================

    public void setTenantId(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId must not be null");
        boolean firstTime = this.tenantId == null;
        this.tenantId = tenantId;
        if (firstTime && !rehydrated) {
            events.add(new IntentStateChangedEvent(
                    UUID.randomUUID(), this.id, this.tenantId,
                    this.version, IntentEventType.CREATED, Instant.now()
            ));
        }
    }

    public void setUserId(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
        this.userId = userId;
    }

    public void setId(UUID id) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        this.id = id;
    }

    // =========================================================================
    // GETTERS
    // =========================================================================

    public UUID               getId()                  { return id; }
    public UUID               getTenantId()            { return tenantId; }
    public UUID               getUserId()              { return userId; }
    public String             getIntentType()          { return intentType; }
    public IntentObjective    getObjective()           { return objective; }
    public IntentConstraints  getConstraints()         { return constraints; }
    public IntentPhase        getPhase()               { return phase; }
    public SatisfactionState  getSatisfactionState()   { return satisfactionState; }
    public int                getRetryCount()          { return retryCount; }
    public int                getMaxRetries()          { return maxRetries; }
    public Budget             getBudget()              { return budget; }
    public DriftScore         getDriftScore()          { return driftScore; }
    public boolean            isTerminal()             { return terminal; }
    public long               getVersion()             { return version; }
    public Instant            getCreatedAt()           { return createdAt; }
    public Instant            getUpdatedAt()           { return updatedAt; }

    /**
     * Convenience accessor — returns the budget ceiling directly.
     * Equivalent to getBudget().getCeilingUsd() but null-safe.
     */
    public Double getCeilingUsd() {
        return budget != null ? budget.getCeilingUsd() : null;
    }

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
}