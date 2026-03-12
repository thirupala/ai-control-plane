package com.decisionmesh.domain.event;

import io.vertx.core.json.Json;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class IntentStateChangedEvent implements DomainEvent {

    private final UUID eventId;
    private final UUID intentId;
    private final UUID tenantId;
    private final long version;
    private final IntentEventType eventType;
    private final Instant occurredAt;

    public IntentStateChangedEvent(
            UUID eventId,
            UUID intentId,
            UUID tenantId,
            long version,
            IntentEventType eventType,
            Instant occurredAt
    ) {
        this.eventId = eventId;
        this.intentId = intentId;
        this.tenantId = tenantId;
        this.version = version;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
    }

    @Override
    public String toJson() {
        return Json.encode(this);
    }

    @Override
    public UUID eventId() {
        return eventId;
    }

    @Override
    public UUID aggregateId() {
        return intentId;
    }

    @Override
    public String aggregateType() {
        return "Intent";
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public IntentEventType eventType() {
        return eventType;
    }

    @Override
    public Instant occurredAt() {
        return occurredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IntentStateChangedEvent that)) return false;
        return version == that.version &&
                Objects.equals(eventId, that.eventId) &&
                Objects.equals(intentId, that.intentId) &&
                Objects.equals(tenantId, that.tenantId) &&
                eventType == that.eventType &&
                Objects.equals(occurredAt, that.occurredAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, intentId, tenantId, version, eventType, occurredAt);
    }

}
