package com.decisionmesh.domain.event;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {


    UUID tenantId();

    UUID eventId();

    IntentEventType eventType();

    long version();

    UUID aggregateId();

    String aggregateType();

    Instant occurredAt();

    String toJson();

}
