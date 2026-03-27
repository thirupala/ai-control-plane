package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "intent_events")
public class IntentEventEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @org.hibernate.annotations.UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    public UUID eventId;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;                   //  maps to aggregateId()

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;                   //  maps to tenantId()

    @Column(name = "version", nullable = false)
    public long version;                    //  maps to version()

    @Column(name = "event_type", nullable = false)
    public String eventType;               //  maps to eventType()

    @Column(name = "aggregate_type", nullable = false)
    public String aggregateType = "Intent"; //  maps to aggregateType()

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;              //  maps to occurredAt()

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    public String payload = "{}";

    @Column(name = "phase_from")
    public String phaseFrom;

    @Column(name = "phase_to")
    public String phaseTo;

    @Column(name = "actor_id")
    public UUID actorId;

    @Column(name = "actor_type")
    public String actorType;

    @Column(name = "plan_id")
    public UUID planId;

    @Column(name = "plan_version")
    public Integer planVersion;

    @Column(name = "execution_id")
    public UUID executionId;

    @Column(name = "attempt_number")
    public Integer attemptNumber;

    @Column(name = "adapter_id")
    public UUID adapterId;

    @Column(name = "policy_id")
    public UUID policyId;

    @Column(name = "drift_score_snapshot", precision = 5, scale = 4)
    public BigDecimal driftScoreSnapshot;

    @Column(name = "cost_usd_snapshot", precision = 12, scale = 6)
    public BigDecimal costUsdSnapshot;

    @Column(name = "risk_score_snapshot", precision = 5, scale = 4)
    public BigDecimal riskScoreSnapshot;

    @Column(name = "trace_id")
    public String traceId;

    @Column(name = "span_id")
    public String spanId;

    @Column(name = "parent_span_id")
    public String parentSpanId;
}