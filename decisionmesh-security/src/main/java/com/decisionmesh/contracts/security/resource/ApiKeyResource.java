package com.decisionmesh.contracts.security.resource;

import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.contracts.security.entity.ApiKey;
import com.decisionmesh.contracts.security.entity.Organization;
import com.decisionmesh.contracts.security.service.ApiKeyService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST endpoints for API key management.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /v1/auth/keys - Create a new API key</li>
 *   <li>GET /v1/auth/keys - List API keys</li>
 *   <li>DELETE /v1/auth/keys/{keyId} - Revoke an API key</li>
 * </ul>
 */
@Path("/v1/auth/keys")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "API Keys", description = "API key management for authentication")
public class ApiKeyResource {

    private static final Logger LOG = Logger.getLogger(ApiKeyResource.class);

    @Inject
    ApiKeyService apiKeyService;

    @Context
    TenantContext tenantContext;

    @Inject
    SecurityIdentity securityIdentity;

    // ============================================
    // CREATE API KEY
    // ============================================

    @POST
    @Operation(
            summary = "Create a new API key",
            description = "Generate a new API key for authentication. The key is only shown once - save it securely!"
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "201",
                    description = "API key created successfully",
                    content = @Content(schema = @Schema(implementation = CreateKeyResponse.class))
            ),
            @APIResponse(
                    responseCode = "400",
                    description = "Invalid request"
            ),
            @APIResponse(
                    responseCode = "401",
                    description = "Unauthorized - user authentication required"
            )
    })
    public Response createKey(@Valid CreateKeyRequest request) {
        UUID tenantId = tenantContext.tenantId();

        // Get user ID from security context (JWT principal or session)
        UUID userId = getUserId();

        // Get organization ID from request or use default organization for tenant
        UUID organizationId = request.organizationId != null ? request.organizationId : getDefaultOrganizationId(String.valueOf(tenantId));

        LOG.infof("Creating API key '%s' for user %s in org %s", request.name, userId, organizationId);

        ApiKeyService.ApiKeyResult result = apiKeyService.createApiKey(
                organizationId,                          // 1. Organization ID
                tenantId,                                // 2. Tenant ID
                userId,                                  // 3. Created by user ID
                request.name,                            // 4. Key name
                Boolean.TRUE.equals(request.isTest),     // 5. Is test key
                request.expiresInDays                    // 6. Expiration days
        );

        return Response.status(Response.Status.CREATED)
                .entity(new CreateKeyResponse(
                        result.keyId,
                        result.key,
                        result.keyPrefix,
                        tenantId,
                        organizationId,
                        result.createdAt,
                        result.expiresAt
                ))
                .build();
    }

    /**
     * Get the authenticated user ID from the security context.
     * This assumes the user is authenticated via JWT or session (not API key).
     */
    private UUID getUserId() {
        if (securityIdentity.isAnonymous()) {
            throw new WebApplicationException(
                    "User authentication required to create API keys",
                    Response.Status.UNAUTHORIZED
            );
        }

        // Try to get userId from JWT claim first
        UUID userId = securityIdentity.getAttribute("userId");

        // Fallback to principal name (email or username)
        if (userId == null) {
            userId = UUID.fromString(securityIdentity.getPrincipal().getName());
        }

        if (userId == null) {
            LOG.error("Unable to determine user ID from security context");
            throw new WebApplicationException(
                    "Unable to determine user identity",
                    Response.Status.UNAUTHORIZED
            );
        }

        return userId;
    }

    /**
     * Get the default organization ID for a tenant.
     * In a multi-org setup, this should be specified in the request.
     */
    private UUID getDefaultOrganizationId(String tenantId) {
        Organization org = Organization.find("tenantId = ?1 ORDER BY createdAt ASC", tenantId)
                .firstResult();

        if (org == null) {
            LOG.errorf("No organization found for tenant: %s", tenantId);
            throw new WebApplicationException(
                    "No organization found for tenant",
                    Response.Status.INTERNAL_SERVER_ERROR
            );
        }

        return org.organizationId;
    }

    // ============================================
    // LIST API KEYS
    // ============================================

    @GET
    @Operation(
            summary = "List API keys",
            description = "List all API keys for the current tenant"
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "API keys retrieved successfully",
                    content = @Content(schema = @Schema(implementation = ListKeysResponse.class))
            )
    })
    public Response listKeys(
            @QueryParam("activeOnly")
            @DefaultValue("true") boolean activeOnly
    ) {
        UUID tenantId = tenantContext.tenantId();

        LOG.debugf("Listing API keys for tenant %s (activeOnly: %s)", tenantId, activeOnly);

        List<ApiKey> keys = apiKeyService.listKeys(tenantId, activeOnly);

        List<KeyListItem> items = keys.stream()
                .map(key -> new KeyListItem(
                        key.keyId,
                        key.keyPrefix,
                        key.name,
                        key.active,
                        key.createdAt,
                        key.lastUsedAt,
                        key.expiresAt,
                        key.usageCount
                ))
                .collect(Collectors.toList());

        return Response.ok(new ListKeysResponse(items)).build();
    }

    // ============================================
    // REVOKE API KEY
    // ============================================

    @DELETE
    @Path("/{keyId}")
    @Operation(
            summary = "Revoke an API key",
            description = "Permanently revoke an API key. This action cannot be undone."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "204",
                    description = "API key revoked successfully"
            ),
            @APIResponse(
                    responseCode = "404",
                    description = "API key not found or unauthorized"
            )
    })
    public Response revokeKey(@PathParam("keyId") UUID keyId) {
        UUID tenantId = tenantContext.tenantId();
        LOG.infof("Revoking API key %s for tenant %s", keyId, tenantId);

        boolean revoked = apiKeyService.revokeKeyForTenant(keyId, tenantId);

        if (!revoked) {
            LOG.warnf("Failed to revoke API key %s for tenant %s", keyId, tenantId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return Response.noContent().build();
    }

    // ============================================
    // REQUEST DTOs
    // ============================================

    /**
     * Request body for creating a new API key.
     */
    public static class CreateKeyRequest {
        @NotBlank(message = "Key name is required")
        public String name;

        public UUID organizationId; // Optional - uses default if not provided

        public Boolean isTest; // Defaults to false (live key)

        @Positive(message = "Expiration must be positive")
        public Integer expiresInDays; // Optional - null means no expiration
    }

    // ============================================
    // RESPONSE DTOs
    // ============================================

    /**
     * Response containing the newly created API key.
     * WARNING: The 'key' field is only shown once - save it securely!
     */
    public static class CreateKeyResponse {
        public UUID keyId;
        public String key; // ONLY RETURNED ONCE - save this!
        public String keyPrefix;
        public UUID tenantId;
        public UUID organizationId;
        public Instant createdAt;
        public Instant expiresAt;

        public CreateKeyResponse(
                UUID keyId,
                String key,
                String keyPrefix,
                UUID tenantId,
                UUID organizationId,
                Instant createdAt,
                Instant expiresAt) {
            this.keyId = keyId;
            this.key = key;
            this.keyPrefix = keyPrefix;
            this.tenantId = tenantId;
            this.organizationId = organizationId;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * Response containing a list of API keys.
     */
    public static class ListKeysResponse {
        public List<KeyListItem> keys;

        public ListKeysResponse(List<KeyListItem> keys) {
            this.keys = keys;
        }
    }

    /**
     * Summary information about an API key (no sensitive data).
     */
    public static class KeyListItem {
        public UUID keyId;
        public String keyPrefix;
        public String name;
        public Boolean active;
        public Instant createdAt;
        public Instant lastUsedAt;
        public Instant expiresAt;
        public Long usageCount;

        public KeyListItem(
                UUID keyId,
                String keyPrefix,
                String name,
                Boolean active,
                Instant createdAt,
                Instant lastUsedAt,
                Instant expiresAt,
                Long usageCount) {
            this.keyId = keyId;
            this.keyPrefix = keyPrefix;
            this.name = name;
            this.active = active;
            this.createdAt = createdAt;
            this.lastUsedAt = lastUsedAt;
            this.expiresAt = expiresAt;
            this.usageCount = usageCount;
        }
    }

    /**
     * Generic error response.
     */
    public static class ErrorResponse {
        public String error;
        public String message;

        public ErrorResponse(String error, String message) {
            this.error = error;
            this.message = message;
        }
    }
}