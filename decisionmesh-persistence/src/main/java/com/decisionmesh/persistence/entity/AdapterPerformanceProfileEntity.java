package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "adapter_performance_profiles",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_profile_adapter_tenant",
                columnNames = {"adapter_id", "tenant_id"}
        ),
        indexes = {
                @Index(name = "idx_profile_tenant",    columnList = "tenant_id"),
                @Index(name = "idx_profile_composite", columnList = "tenant_id, composite_score")
        }
)
public class AdapterPerformanceProfileEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "adapter_id", nullable = false)
    public UUID adapterId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "ema_cost", nullable = false)
    public double emaCost = 0.0;

    @Column(name = "ema_latency_ms", nullable = false)
    public double emaLatencyMs = 0.0;

    @Column(name = "ema_success_rate", nullable = false)
    public double emaSuccessRate = 1.0;

    @Column(name = "ema_risk_score", nullable = false)
    public double emaRiskScore = 0.0;

    @Column(name = "ema_confidence", nullable = false)
    public double emaConfidence = 0.0;

    @Column(name = "composite_score", nullable = false)
    public double compositeScore = 0.0;

    @Column(name = "execution_count", nullable = false)
    public long executionCount = 0;

    @Column(name = "success_count", nullable = false)
    public long successCount = 0;

    @Column(name = "failure_count", nullable = false)
    public long failureCount = 0;

    @Column(name = "cold_start", nullable = false)
    public boolean coldStart = true;

    @Column(name = "cold_start_threshold", nullable = false)
    public int coldStartThreshold = 10;

    @Column(name = "is_degraded", nullable = false)
    public boolean isDegraded = false;

    @Column(name = "degraded_since")
    public Instant degradedSince;

    @Column(name = "degraded_reason", length = 255)
    public String degradedReason;

    @Column(name = "last_executed_at")
    public Instant lastExecutedAt;

    @Version
    @Column(name = "version", nullable = false)
    public int version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Finders ───────────────────────────────────────────────────────

    public static Optional<AdapterPerformanceProfileEntity> findByAdapterAndTenant(
            UUID adapterId, UUID tenantId) {
        return find("adapterId = ?1 AND tenantId = ?2", adapterId, tenantId).firstResultOptional();
    }

    public static java.util.List<AdapterPerformanceProfileEntity> findActiveByTenant(UUID tenantId) {
        return list("tenantId = ?1 AND isDegraded = false ORDER BY compositeScore DESC", tenantId);
    }
}