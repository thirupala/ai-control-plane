package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API Key entity for authentication.
 * Stores hashed keys (never plaintext) with metadata for multi-tenant access control.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey extends PanacheEntityBase {

    @Id
    @Column(name = "key_id")
    public UUID keyId;

    @Column(name = "organization_id", nullable = false)
    public UUID organizationId;

    @Column(name = "created_by_userId", nullable = false)
    public UUID createdByUserId;

    @Column(name = "key_hash", nullable = false, unique = true)
    public String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 20)
    public String keyPrefix;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "name")
    public String name;

    @Column(name = "scopes")
    @JdbcTypeCode(SqlTypes.JSON)
    public String scopes; // JSON array of scopes

    @Column(name = "active", nullable = false)
    public Boolean active = true;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    @Column(name = "revoked_by")
    public String revokedBy;

    @Column(name = "last_used_at")
    public Instant lastUsedAt;

    @Column(name = "usage_count", nullable = false)
    public Long usageCount = 0L;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "expires_at")
    public Instant expiresAt;

    @Column(name = "ip_whitelist")
    @JdbcTypeCode(SqlTypes.JSON)
    public String ipWhitelist;

    @Column(name = "rate_limit")
    public Integer rateLimit;

    // ============================================
    // STATIC QUERIES
    // ============================================

    /**
     * Find an active API key by its hash.
     */
    public static ApiKey findByHash(String keyHash) {
        return find("keyHash = ?1 AND active = true", keyHash).firstResult();
    }

    /**
     * Find all API keys for a tenant.
     */
    public static List<ApiKey> findByTenant(UUID tenantId) {
        return list("tenantId = ?1 ORDER BY createdAt DESC", tenantId);
    }

    /**
     * Find only active API keys for a tenant.
     */
    public static List<ApiKey> findActiveTenantKeys(UUID tenantId) {
        return list("tenantId = ?1 AND active = true ORDER BY createdAt DESC", tenantId);
    }

    // ============================================
    // BUSINESS METHODS
    // ============================================

    /**
     * Check if the key has expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if the key is valid (active and not expired).
     */
    public boolean isValid() {
        return active && !isExpired();
    }

    /**
     * Revoke this API key.
     */
    public void revoke(String revokedBy) {
        this.active = false;
        this.revokedAt = Instant.now();
        this.revokedBy = revokedBy;
    }

    /**
     * Record that this key was just used.
     */
    public void recordUsage() {
        this.lastUsedAt = Instant.now();
        this.usageCount++;
    }

    /**
     * Check if this key has a specific scope.
     */
    public boolean hasScope(String scope) {
        if (scopes == null || scopes.isEmpty()) {
            return true; // No scopes = full access
        }
        return scopes.contains("\"" + scope + "\"") || scopes.contains("\"*\"");
    }
}