package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for the adapters table.
 *
 * Used by CostAnalyticsService for the costByAdapter JOIN query
 * (adapter name is stored here, not denormalized into spend_records).
 *
 * Also the backing entity for AdapterResource CRUD endpoints.
 */
@Entity
@Table(
        name = "adapters",
        indexes = {
                @Index(name = "idx_adapters_tenant",   columnList = "tenant_id"),
                @Index(name = "idx_adapters_active",   columnList = "tenant_id, is_active"),
                @Index(name = "idx_adapters_type",     columnList = "tenant_id, adapter_type"),
                @Index(name = "idx_adapters_provider", columnList = "tenant_id, provider"),
        }
)
public class AdapterEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "adapter_type", nullable = false, length = 100)
    public String adapterType;

    @Column(name = "provider", nullable = false, length = 100)
    public String provider;

    @Column(name = "model_id", length = 255)
    public String modelId;

    @Column(name = "region", length = 100)
    public String region;

    @Column(name = "base_cost_per_token", precision = 18, scale = 8)
    public java.math.BigDecimal baseCostPerToken;

    @Column(name = "max_tokens_per_call")
    public Integer maxTokensPerCall;

    @Column(name = "avg_latency_ms")
    public Long avgLatencyMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb", nullable = false)
    public Map<String, Object> config;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capability_flags", columnDefinition = "jsonb", nullable = false)
    public Map<String, Object> capabilityFlags;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_intent_types", columnDefinition = "jsonb", nullable = false)
    public List<String> allowedIntentTypes;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    // ── Reactive finders ──────────────────────────────────────────────────────

    public static Uni<List<AdapterEntity>> findByTenant(UUID tenantId) {
        return find("tenantId = ?1 order by createdAt asc", tenantId).list();
    }

    public static Uni<List<AdapterEntity>> findActiveByTenant(UUID tenantId) {
        return find("tenantId = ?1 and isActive = true order by name asc", tenantId).list();
    }

    public static Uni<AdapterEntity> findByTenantAndId(UUID tenantId, UUID adapterId) {
        return find("tenantId = ?1 and id = ?2", tenantId, adapterId).firstResult();
    }
}
