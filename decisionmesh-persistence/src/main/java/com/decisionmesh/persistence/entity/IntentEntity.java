package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "intents",
        indexes = {
                @Index(name = "idx_intents_tenant",       columnList = "tenant_id"),
                @Index(name = "idx_intents_tenant_phase", columnList = "tenant_id, phase")
        }
)
public class IntentEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    // Fixed: was String — tenantId is always a UUID
    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "phase", nullable = false, length = 50)
    public String phase;

    @Column(name = "satisfaction", length = 50)
    public String satisfaction;

    @Column(name = "retry_count", nullable = false)
    public int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    public int maxRetries = 3;

    @Column(name = "drift_score", nullable = false)
    public double driftScore = 0.0;

    @Version
    @Column(name = "version", nullable = false)
    public long version = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    // ── Reactive finders ──────────────────────────────────────────────

    public static Uni<IntentEntity> findByTenantAndId(UUID tenantId, UUID intentId) {
        return find("tenantId = ?1 and id = ?2", tenantId, intentId).firstResult();
    }
}
