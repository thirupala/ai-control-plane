package com.decisionmesh.contracts.security.context;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.WebApplicationException;

import java.util.UUID;

/**
 * Request-scoped context holding tenant, user, and API key information.
 * Set by authentication filters and used throughout the request lifecycle.
 */
@RequestScoped
public class TenantContext {

    private UUID tenantId;
    private String apiKeyId;
    private UUID userId;
    private String authType;

    public UUID tenantId() {
        if (tenantId == null) {
            throw new WebApplicationException("Tenant not resolved", 401);
        }
        return tenantId;
    }

    public UUID userId() {
        return userId;
    }

    public boolean isApiKey() {
        return apiKeyId != null;
    }

    public void set(UUID tenantId, String apiKeyId, UUID userId, String authType) {
        if (this.tenantId != null) {
            throw new IllegalStateException("TenantContext already set");
        }
        this.tenantId = tenantId;
        this.apiKeyId = apiKeyId;
        this.userId = userId;
        this.authType = authType;
    }
}