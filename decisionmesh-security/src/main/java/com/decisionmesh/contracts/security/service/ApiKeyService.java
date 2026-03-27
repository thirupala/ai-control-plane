package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.ApiKeyEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service for API key generation, validation, and management.
 */
@ApplicationScoped
public class ApiKeyService {

    private static final Logger LOG = Logger.getLogger(ApiKeyService.class);

    private static final String KEY_PREFIX_LIVE = "sk_live_";
    private static final String KEY_PREFIX_TEST = "sk_test_";
    private static final int KEY_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ============================================
    // KEY GENERATION
    // ============================================

    /**
     * Create a new API key.
     */
    @Transactional
    public ApiKeyResult createApiKey(
            UUID organizationId,
            UUID tenantId,
            UUID createdByUserId,
            String name,
            boolean isTest,
            Integer expiresInDays) {

        // Generate cryptographically secure random key
        byte[] keyBytes = new byte[KEY_LENGTH];
        SECURE_RANDOM.nextBytes(keyBytes);
        String randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);

        // Create full key with prefix
        String prefix = isTest ? KEY_PREFIX_TEST : KEY_PREFIX_LIVE;
        String fullKey = prefix + randomPart;

        // Hash the key for storage
        String keyHash = hashKey(fullKey);

        // Create entity
        ApiKeyEntity apiKey = new ApiKeyEntity();
        apiKey.keyId = UUID.randomUUID();
        apiKey.organizationId = organizationId;
        apiKey.tenantId = tenantId;
        apiKey.createdByUserId = createdByUserId;
        apiKey.keyHash = keyHash;
        apiKey.keyPrefix = fullKey.substring(0, Math.min(20, fullKey.length()));
        apiKey.name = name;
        apiKey.scopes = "[\"*\"]"; // Full access by default
        apiKey.active = true;
        apiKey.createdAt = Instant.now();
        apiKey.createdBy = createdByUserId.toString();
        apiKey.usageCount = 0L;

        if (expiresInDays != null && expiresInDays > 0) {
            apiKey.expiresAt = Instant.now().plus(expiresInDays, ChronoUnit.DAYS);
        }

        apiKey.persist();

        LOG.infof("Created API key for tenant %s: %s... (expires: %s)",
                tenantId, apiKey.keyPrefix, apiKey.expiresAt);

        return new ApiKeyResult(apiKey.keyId, fullKey, apiKey.keyPrefix, apiKey.createdAt, apiKey.expiresAt);
    }

    // ============================================
    // KEY VALIDATION
    // ============================================

    /**
     * Validate an API key and return the associated entity if valid.
     */
    @Transactional
    public ApiKeyEntity validateAndGetKey(String providedKey) {
        if (providedKey == null || providedKey.isBlank()) {
            return null;
        }

        // Validate format
        if (!providedKey.startsWith(KEY_PREFIX_LIVE) && !providedKey.startsWith(KEY_PREFIX_TEST)) {
            LOG.debugf("Invalid key format: %s", providedKey.substring(0, Math.min(10, providedKey.length())));
            return null;
        }

        // Hash and lookup
        String keyHash = hashKey(providedKey);
        ApiKeyEntity apiKey = ApiKeyEntity.findByHash(keyHash);

        if (apiKey == null) {
            LOG.debugf("API key not found: %s...", providedKey.substring(0, Math.min(15, providedKey.length())));
            return null;
        }

        // Check if valid
        if (!apiKey.isValid()) {
            LOG.warnf("Invalid/expired API key used: %s (tenant: %s)", apiKey.keyPrefix, apiKey.tenantId);
            return null;
        }

        // Record usage
        apiKey.recordUsage();

        LOG.debugf("Valid API key: %s (tenant: %s, usage: %d)",
                apiKey.keyPrefix, apiKey.tenantId, apiKey.usageCount);

        return apiKey;
    }

    // ============================================
    // KEY MANAGEMENT
    // ============================================

    /**
     * Revoke an API key.
     */
    @Transactional
    public boolean revokeKeyForTenant(UUID keyId, UUID tenantId) {
        ApiKeyEntity apiKey = ApiKeyEntity.findById(keyId);
        if (apiKey == null) {
            return false;
        }

        // Prevent cross-tenant access
        if (!apiKey.tenantId.equals(tenantId)) {
            LOG.warnf("Attempted cross-tenant key revocation: key %s, tenant %s", keyId, tenantId);
            return false;
        }

        apiKey.revoke("api");
        apiKey.persist();

        LOG.infof("Revoked API key %s for tenant %s", apiKey.keyPrefix, tenantId);
        return true;
    }

    /**
     * List API keys for a tenant.
     */
    @Transactional
    public List<ApiKeyEntity> listKeys(UUID tenantId, boolean activeOnly) {
        if (activeOnly) {
            return ApiKeyEntity.list("tenantId = ?1 AND active = true ORDER BY createdAt DESC", tenantId);
        }
        return ApiKeyEntity.list("tenantId = ?1 ORDER BY createdAt DESC", tenantId);
    }

    // ============================================
    // UTILITY
    // ============================================

    /**
     * Hash a key using SHA-256.
     */
    private String hashKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ============================================
    // RESULT DTO
    // ============================================

    /**
     * Result of API key creation.
     */
    public static class ApiKeyResult {
        public UUID keyId;
        public String key; // ONLY RETURNED ONCE
        public String keyPrefix;
        public Instant createdAt;
        public Instant expiresAt;

        public ApiKeyResult(UUID keyId, String key, String keyPrefix, Instant createdAt, Instant expiresAt) {
            this.keyId = keyId;
            this.key = key;
            this.keyPrefix = keyPrefix;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }
}