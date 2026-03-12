package com.decisionmesh.contracts.security.resource.dto;

import java.util.UUID;

/**
 * Signup response.
 * ⚠️ apiKey is returned ONCE and must never be logged or stored.
 */
public class SignupResponse {

    public final String userId;
    public final UUID tenantId;

    // SENSITIVE – shown once only
    public final String apiKey;

    public final String warning =
            "Save this API key now. It will not be shown again.";

    public SignupResponse(
            String userId,
            UUID tenantId,
            String apiKey
    ) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.apiKey = apiKey;
    }
}
