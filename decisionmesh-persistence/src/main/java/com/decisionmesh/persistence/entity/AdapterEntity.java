package com.decisionmesh.persistence.entity;

import com.decisionmesh.contracts.security.converter.StringListJsonConverter;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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

    // ── JSONB fields — initialised to safe defaults so nullable=false columns
    // are never written as SQL NULL by Hibernate on first persist.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb", nullable = false)
    public Map<String, Object> config = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capability_flags", columnDefinition = "jsonb", nullable = false)
    public Map<String, Object> capabilityFlags = new HashMap<>();

    // ⚠ DO NOT use @JdbcTypeCode(SqlTypes.JSON) here.
    //
    // Hibernate Reactive's ReactiveJsonJdbcType routes every @JdbcTypeCode(JSON)
    // value through Vert.x new JsonObject(jsonString) before binding to the wire.
    // JsonObject is hardcoded for JSON *objects* ({...}) — it throws DecodeException
    // when given a JSON *array* ([...]), regardless of the Java field type:
    //
    //   io.vertx.core.json.DecodeException: Failed to decode:
    //   Cannot deserialize value of type LinkedHashMap from Array value
    //
    // @Convert bypasses ReactiveJsonJdbcType entirely: Jackson serialises
    // List<String> → "[\"CHAT\",\"SUMMARIZATION\"]" and Hibernate binds it
    // as a plain String to the jsonb column. PostgreSQL JSONB operators
    // (@> and = '[]'::jsonb) used by AdapterRegistry still work correctly.
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "allowed_intent_types", columnDefinition = "jsonb", nullable = false)
    public List<String> allowedIntentTypes = new ArrayList<>();

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    // ── Lifecycle guard ───────────────────────────────────────────────────────
    // Secondary safety net: if a caller sets a JSONB field to null after
    // construction, the @PrePersist resets it to the empty default so Hibernate
    // never attempts to write SQL NULL into a nullable=false jsonb column.
    @PrePersist
    @PreUpdate
    void initJsonbDefaults() {
        if (config              == null) config              = new HashMap<>();
        if (capabilityFlags     == null) capabilityFlags     = new HashMap<>();
        if (allowedIntentTypes  == null) allowedIntentTypes  = new ArrayList<>();
    }

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