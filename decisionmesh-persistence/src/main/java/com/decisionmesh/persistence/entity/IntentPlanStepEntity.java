package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "intent_plan_steps", indexes = {
        @Index(name = "idx_plan_steps_plan",   columnList = "plan_id"),
        @Index(name = "idx_plan_steps_intent", columnList = "intent_id")
})
public class IntentPlanStepEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "plan_id", nullable = false)
    public UUID planId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    @Column(name = "step_order", nullable = false)
    public int stepOrder = 0;

    @Column(name = "adapter_id")
    public UUID adapterId;          // null = dynamic selection by engine

    @Column(name = "step_type", nullable = false, length = 50)
    public String stepType = "PRIMARY";  // PRIMARY, FALLBACK, PARALLEL, ENSEMBLE_MEMBER

    @Column(name = "is_conditional", nullable = false)
    public boolean isConditional = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_expr", columnDefinition = "jsonb")
    public Map<String, Object> conditionExpr;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> configSnapshot;

    @Column(name = "estimated_cost_usd", precision = 12, scale = 6)
    public BigDecimal estimatedCostUsd;

    @Column(name = "estimated_latency_ms")
    public Long estimatedLatencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    // ── Finders ───────────────────────────────────────────────────────

    public static List<IntentPlanStepEntity> findByPlanOrdered(UUID planId) {
        return list("planId = ?1 ORDER BY stepOrder ASC", planId);
    }

    public static List<IntentPlanStepEntity> findByIntentOrdered(UUID intentId) {
        return list("intentId = ?1 ORDER BY stepOrder ASC", intentId);
    }
}