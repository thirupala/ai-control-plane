package com.decisionmesh.domain.intent;

import com.decisionmesh.domain.event.DomainEvent;
import com.decisionmesh.domain.event.IntentEventType;
import com.decisionmesh.domain.event.IntentStateChangedEvent;
import com.decisionmesh.domain.intent.value.IntentConstraints;
import com.decisionmesh.domain.intent.value.IntentObjective;
import com.decisionmesh.domain.value.Budget;
import com.decisionmesh.domain.value.DriftScore;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class Intent {

    private final UUID id;
    private UUID tenantId;
    private final UUID userId;

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

    private final List<DomainEvent> events = new ArrayList<>();

    // ================= FULL CONSTRUCTOR (rehydration) =================

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

        if (!rehydrated) {
            emit(IntentEventType.CREATED);
        }
    }

    // ================= PRIVATE CONSTRUCTOR =================

    private Intent(UUID id,
                   UUID tenantId,
                   UUID userId,
                   String intentType,
                   IntentObjective objective,
                   IntentConstraints constraints,
                   IntentPhase phase,
                   SatisfactionState satisfactionState,
                   int retryCount,
                   Budget budget,
                   DriftScore driftScore,
                   boolean terminal,
                   long version,
                   Instant createdAt,
                   Instant updatedAt) {

        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.intentType = intentType;
        this.objective = objective;
        this.constraints = constraints;
        this.phase = phase;
        this.satisfactionState = satisfactionState;
        this.retryCount = retryCount;
        this.budget = budget;
        this.driftScore = driftScore;
        this.terminal = terminal;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;

        emit(IntentEventType.CREATED);
    }

    // ================= FACTORY =================

    public static Intent create(UUID tenantId,
                                UUID userId,
                                String intentType,
                                IntentObjective objective,
                                IntentConstraints constraints,
                                Budget budget) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(intentType);
        Objects.requireNonNull(objective);
        Objects.requireNonNull(constraints);
        Objects.requireNonNull(budget);

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
                budget,
                DriftScore.of(0.0),
                false,
                0L,
                Instant.now(),
                Instant.now()
        );
    }

    /**
     * Jackson deserialization factory — used when Intent is received as a REST request body.
     * Only client-supplied fields are required; server-side fields (id, tenantId, phase, etc.)
     * are set later by the orchestrator via Intent.create().
     */
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

        // Placeholder UUIDs — replaced by orchestrator when Intent.create() is called
        // with the real tenantId and userId from the security context
        return new Intent(
                UUID.randomUUID(),
                null, // tenantId — set by orchestrator from token
                null, // userId   — set by orchestrator from token
                intentType,
                objective,
                constraints,
                IntentPhase.CREATED,
                SatisfactionState.UNKNOWN,
                0,
                budget,
                DriftScore.of(0.0),
                false,
                0L,
                Instant.now(),
                Instant.now()
        );
    }

    // ================= LIFECYCLE =================

    public String toJson() {
        return Json.encode(this);
    }

    public static Intent fromJson(String json) {

        JsonObject obj = new JsonObject(json);

        return new Intent(
                UUID.fromString(obj.getString("id")),
                UUID.fromString(obj.getString("tenantId")),
                UUID.fromString(obj.getString("userId")),
                obj.getString("intentType"),

                JsonObject.mapFrom(obj.getJsonObject("objective"))
                        .mapTo(IntentObjective.class),

                JsonObject.mapFrom(obj.getJsonObject("constraints"))
                        .mapTo(IntentConstraints.class),

                IntentPhase.valueOf(obj.getString("phase")),
                SatisfactionState.valueOf(obj.getString("satisfactionState")),

                obj.getInteger("retryCount"),
                obj.getInteger("maxRetries"),

                JsonObject.mapFrom(obj.getJsonObject("budget"))
                        .mapTo(Budget.class),

                JsonObject.mapFrom(obj.getJsonObject("driftScore"))
                        .mapTo(DriftScore.class),

                obj.getBoolean("terminal"),
                obj.getLong("version"),

                Instant.parse(obj.getString("createdAt")),
                Instant.parse(obj.getString("updatedAt")),

                true // rehydrated flag
        );
    }

    public void startPlanning() {
        transition(IntentPhase.CREATED, IntentPhase.PLANNING, IntentEventType.PLANNING_STARTED);
    }

    public void markPlanned() {
        transition(IntentPhase.PLANNING, IntentPhase.PLANNED, IntentEventType.PLANNED);
    }

    public void markExecuting() {
        transition(IntentPhase.PLANNED, IntentPhase.EXECUTING, IntentEventType.EXECUTION_STARTED);
    }

    public void markEvaluating() {
        transition(
                IntentPhase.EXECUTING,
                IntentPhase.EVALUATING,
                IntentEventType.EVALUATION_STARTED
        );
    }

    public void markSatisfied() {
        transition(IntentPhase.EXECUTING, IntentPhase.COMPLETED, IntentEventType.SATISFIED);
        this.satisfactionState = SatisfactionState.SATISFIED;
        this.terminal = true;
        enforceInvariant();
    }

    public void markViolated() {
        transition(IntentPhase.EXECUTING, IntentPhase.COMPLETED, IntentEventType.VIOLATED);
        this.satisfactionState = SatisfactionState.VIOLATED;
        this.terminal = true;
        enforceInvariant();
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void scheduleRetry() {

        if (phase != IntentPhase.EXECUTING)
            throw new IllegalStateException("Retry only from EXECUTING");

        if (retryCount >= constraints.maxRetries())
            throw new IllegalStateException("Max retries exceeded");

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

    // ================= INTERNAL =================

    private void transition(IntentPhase expected, IntentPhase next, IntentEventType eventType) {

        if (terminal)
            throw new IllegalStateException("Cannot transition terminal intent");

        if (phase != expected)
            throw new IllegalStateException("Invalid transition");

        phase = next;
        version++;
        touch();
        emit(eventType);
    }

    private void enforceInvariant() {
        if (phase == IntentPhase.COMPLETED &&
                satisfactionState == SatisfactionState.UNKNOWN)
            throw new IllegalStateException("Completed intent must have satisfaction");
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private void emit(IntentEventType type) {
        events.add(new IntentStateChangedEvent(
                UUID.randomUUID(),
                this.id,
                this.tenantId,
                this.version,
                type,
                Instant.now()
        ));
    }

    // ================= GETTERS =================

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public IntentPhase getPhase() { return phase; }
    public SatisfactionState getSatisfactionState() { return satisfactionState; }
    public DriftScore getDriftScore() { return driftScore; }
    public Budget getBudget() { return budget; }
    public long getVersion() { return version; }
    public boolean isTerminal() { return terminal; }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    public IntentConstraints getConstraints() {
        return constraints;
    }

    public IntentObjective getObjective() {
        return objective;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getIntentType() {
        return intentType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<DomainEvent> getEvents() {
        return events;
    }

    public void updateDriftScore(BigDecimal driftScore, UUID id) {}

    public void setTenantId(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("TenantId must not be null");
        this.tenantId = tenantId;
    }
}