package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "intent_plans", indexes = {
        @Index(name = "idx_plans_intent", columnList = "intent_id"),
        @Index(name = "idx_plans_tenant", columnList = "tenant_id")
})
public class IntentPlanEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "plan_version", nullable = false)
    public int planVersion = 1;

    @Column(name = "strategy", nullable = false, length = 50)
    public String strategy = "SINGLE_ADAPTER";  // SINGLE_ADAPTER, RANKED_FALLBACK, PARALLEL_RACE, ENSEMBLE

    @Column(name = "status", nullable = false, length = 50)
    public String status = "ACTIVE";            // ACTIVE, SUPERSEDED, ABANDONED

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ranking_snapshot", nullable = false, columnDefinition = "jsonb")
    public List<Map<String, Object>> rankingSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "budget_allocation", nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> budgetAllocation;

    @Column(name = "was_exploration", nullable = false)
    public boolean wasExploration = false;

    @Column(name = "planner_notes", columnDefinition = "TEXT")
    public String plannerNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    // ── Finders ───────────────────────────────────────────────────────

    public static Optional<IntentPlanEntity> findActiveByIntent(UUID intentId) {
        return find("intentId = ?1 AND status = 'ACTIVE' ORDER BY planVersion DESC",
                intentId).firstResultOptional();
    }

    public static List<IntentPlanEntity> findAllByIntent(UUID intentId) {
        return list("intentId = ?1 ORDER BY planVersion ASC", intentId);
    }

    public static Optional<IntentPlanEntity> findLatestByIntent(UUID intentId) {
        return find("intentId = ?1 ORDER BY planVersion DESC", intentId).firstResultOptional();
    }
}