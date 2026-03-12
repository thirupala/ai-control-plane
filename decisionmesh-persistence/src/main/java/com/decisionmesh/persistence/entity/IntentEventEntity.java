package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "intent_events")
public class IntentEventEntity extends PanacheEntityBase {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    public UUID eventId;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    // NEW FIELDS
    @Column(name = "aggregate_id", nullable = false)
    public UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false)
    public String aggregateType;

    @Column(name = "version", nullable = false)
    public long version;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "event_type", nullable = false)
    public String eventType;

    @Column(name = "payload", columnDefinition = "jsonb")
    public String payload;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;
}
