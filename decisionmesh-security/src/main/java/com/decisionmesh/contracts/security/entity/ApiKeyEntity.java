package com.decisionmesh.contracts.security.entity;

import com.decisionmesh.contracts.security.converter.StringListJsonConverter;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "api_keys",
        indexes = {
                @Index(name = "idx_api_keys_tenant",   columnList = "tenant_id"),
                @Index(name = "idx_api_keys_key_hash", columnList = "key_hash")
        }
)
public class ApiKeyEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "key_id", updatable = false, nullable = false)
    public UUID keyId;

    @Column(name = "organization_id", nullable = false)
    public UUID organizationId;

    @Column(name = "created_by_userId", nullable = false)
    public UUID createdByUserId;

    @Column(name = "key_hash", nullable = false, unique = true, length = 255)
    public String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 20)
    public String keyPrefix;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "name", length = 255)
    public String name;

    /**
     * Scopes as JSONB string array — e.g. ["read", "write"].
     * Uses AttributeConverter — see StringListJsonConverter for why.
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "scopes", columnDefinition = "jsonb")
    public List<String> scopes = new ArrayList<>();

    @Column(name = "active", nullable = false)
    public Boolean active = true;

    @Column(name = "revoked_at")
    public OffsetDateTime revokedAt;

    @Column(name = "revoked_by", length = 255)
    public String revokedBy;

    @Column(name = "last_used_at")
    public OffsetDateTime lastUsedAt;

    @Column(name = "usage_count", nullable = false)
    public Long usageCount = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "created_by", length = 255)
    public String createdBy;

    @Column(name = "expires_at")
    public OffsetDateTime expiresAt;

    /**
     * IP whitelist as JSONB string array — e.g. ["192.168.1.0/24"].
     * Uses AttributeConverter — see StringListJsonConverter for why.
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "ip_whitelist", columnDefinition = "jsonb")
    public List<String> ipWhitelist = new ArrayList<>();

    @Column(name = "rate_limit")
    public Integer rateLimit;

    // ── Reactive finders ──────────────────────────────────────────────────────

    public static Uni<ApiKeyEntity> findByHash(String keyHash) {
        return find("keyHash = ?1 and active = true", keyHash).firstResult();
    }

    public static Uni<List<ApiKeyEntity>> findByTenant(UUID tenantId) {
        return find("tenantId = ?1 order by createdAt desc", tenantId).list();
    }

    public static Uni<List<ApiKeyEntity>> findActiveTenantKeys(UUID tenantId) {
        return find("tenantId = ?1 and active = true order by createdAt desc", tenantId).list();
    }

    // ── Business methods ──────────────────────────────────────────────────────

    public boolean isExpired() {
        return expiresAt != null && OffsetDateTime.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return active && !isExpired();
    }

    public void revoke(String revokedBy) {
        this.active    = false;
        this.revokedAt = OffsetDateTime.now();
        this.revokedBy = revokedBy;
    }

    public void recordUsage() {
        this.lastUsedAt = OffsetDateTime.now();
        this.usageCount++;
    }

    public boolean hasScope(String scope) {
        if (scopes == null || scopes.isEmpty()) {
            return true;
        }
        return scopes.contains(scope) || scopes.contains("*");
    }
}