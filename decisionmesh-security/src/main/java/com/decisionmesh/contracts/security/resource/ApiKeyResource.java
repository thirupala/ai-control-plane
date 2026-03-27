package com.decisionmesh.contracts.security.resource;

import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.contracts.security.entity.ApiKeyEntity;
import com.decisionmesh.contracts.security.entity.OrganizationEntity;
import com.decisionmesh.contracts.security.service.ApiKeyService;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/v1/auth/keys")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "API Keys", description = "API key management for authentication")
public class ApiKeyResource {

    private static final Logger LOG = Logger.getLogger(ApiKeyResource.class);

    @Inject ApiKeyService    apiKeyService;
    @Context TenantContext   tenantContext;
    @Inject SecurityIdentity securityIdentity;

    // ── Create ────────────────────────────────────────────────────────────────

    @POST
    @Operation(summary = "Create a new API key",
            description = "The key is only shown once — save it securely!")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "API key created",
                    content = @Content(schema = @Schema(implementation = CreateKeyResponse.class))),
            @APIResponse(responseCode = "400", description = "Invalid request"),
            @APIResponse(responseCode = "401", description = "Unauthorized")
    })
    public Uni<Response> createKey(@Valid CreateKeyRequest request) {
        UUID tenantId = tenantContext.tenantId();
        UUID userId   = getUserId();

        // Resolve organizationId: use request value or look up the tenant's default org
        Uni<UUID> orgIdUni = request.organizationId != null
                ? Uni.createFrom().item(request.organizationId)
                : resolveDefaultOrganizationId(tenantId);

        return orgIdUni.flatMap(organizationId -> {
            LOG.infof("Creating API key '%s' for user %s in org %s",
                    request.name, userId, organizationId);

            return apiKeyService.createApiKey(
                    organizationId,
                    tenantId,
                    userId,
                    request.name,
                    Boolean.TRUE.equals(request.isTest),
                    request.expiresInDays
            ).map(result -> Response.status(Response.Status.CREATED)
                    .entity(new CreateKeyResponse(
                            result.keyId,
                            result.key,
                            result.keyPrefix,
                            tenantId,
                            organizationId,
                            result.createdAt,
                            result.expiresAt))
                    .build());
        });
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GET
    @Operation(summary = "List API keys",
            description = "List all API keys for the current tenant")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Keys retrieved",
                    content = @Content(schema = @Schema(implementation = ListKeysResponse.class)))
    })
    public Uni<Response> listKeys(
            @QueryParam("activeOnly") @DefaultValue("true") boolean activeOnly) {

        UUID tenantId = tenantContext.tenantId();
        LOG.debugf("Listing API keys for tenant %s (activeOnly: %s)", tenantId, activeOnly);

        return apiKeyService.listKeys(tenantId, activeOnly)
                .map(keys -> {
                    List<KeyListItem> items = keys.stream()
                            .map(key -> new KeyListItem(
                                    key.keyId,
                                    key.keyPrefix,
                                    key.name,
                                    key.active,
                                    key.createdAt,
                                    key.lastUsedAt,
                                    key.expiresAt,
                                    key.usageCount))
                            .collect(Collectors.toList());
                    return Response.ok(new ListKeysResponse(items)).build();
                });
    }

    // ── Revoke ────────────────────────────────────────────────────────────────

    @DELETE
    @Path("/{keyId}")
    @Operation(summary = "Revoke an API key",
            description = "Permanently revoke an API key. Cannot be undone.")
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Revoked"),
            @APIResponse(responseCode = "404", description = "Not found or unauthorized")
    })
    public Uni<Response> revokeKey(@PathParam("keyId") UUID keyId) {
        UUID tenantId = tenantContext.tenantId();
        LOG.infof("Revoking API key %s for tenant %s", keyId, tenantId);

        return apiKeyService.revokeKeyForTenant(keyId, tenantId)
                .map(revoked -> {
                    if (!revoked) {
                        LOG.warnf("Failed to revoke key %s for tenant %s", keyId, tenantId);
                        return Response.status(Response.Status.NOT_FOUND).build();
                    }
                    return Response.noContent().build();
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID getUserId() {
        if (securityIdentity.isAnonymous()) {
            throw new WebApplicationException(
                    "User authentication required to create API keys",
                    Response.Status.UNAUTHORIZED);
        }
        UUID userId = securityIdentity.getAttribute("userId");
        if (userId == null) {
            try {
                userId = UUID.fromString(securityIdentity.getPrincipal().getName());
            } catch (IllegalArgumentException e) {
                LOG.error("Unable to determine user ID from security context");
                throw new WebApplicationException(
                        "Unable to determine user identity",
                        Response.Status.UNAUTHORIZED);
            }
        }
        return userId;
    }

    /**
     * Reactively resolves the default organization ID for a tenant.
     * Uses Panache.withSession() since this is a read-only lookup.
     */
    private Uni<UUID> resolveDefaultOrganizationId(UUID tenantId) {
        return Panache.withSession(() ->
                OrganizationEntity
                        .<OrganizationEntity>find(
                                "tenantId = ?1 and isActive = true order by createdAt asc",
                                tenantId)
                        .firstResult()
                        .map(org -> {
                            if (org == null) {
                                LOG.errorf("No organization found for tenant: %s", tenantId);
                                throw new WebApplicationException(
                                        "No organization found for tenant",
                                        Response.Status.INTERNAL_SERVER_ERROR);
                            }
                            return org.id;
                        })
        );
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public static class CreateKeyRequest {
        @NotBlank(message = "Key name is required")
        public String name;
        public UUID    organizationId;
        public Boolean isTest;
        @Positive(message = "Expiration must be positive")
        public Integer expiresInDays;
    }

    public static class CreateKeyResponse {
        public UUID           keyId;
        public String         key;             // ONLY RETURNED ONCE — save this!
        public String         keyPrefix;
        public UUID           tenantId;
        public UUID           organizationId;
        public OffsetDateTime createdAt;
        public OffsetDateTime expiresAt;

        public CreateKeyResponse(UUID keyId, String key, String keyPrefix,
                                 UUID tenantId, UUID organizationId,
                                 OffsetDateTime createdAt, OffsetDateTime expiresAt) {
            this.keyId          = keyId;
            this.key            = key;
            this.keyPrefix      = keyPrefix;
            this.tenantId       = tenantId;
            this.organizationId = organizationId;
            this.createdAt      = createdAt;
            this.expiresAt      = expiresAt;
        }
    }

    public static class ListKeysResponse {
        public List<KeyListItem> keys;
        public ListKeysResponse(List<KeyListItem> keys) { this.keys = keys; }
    }

    public static class KeyListItem {
        public UUID           keyId;
        public String         keyPrefix;
        public String         name;
        public Boolean        active;
        public OffsetDateTime createdAt;
        public OffsetDateTime lastUsedAt;
        public OffsetDateTime expiresAt;
        public Long           usageCount;

        public KeyListItem(UUID keyId, String keyPrefix, String name,
                           Boolean active, OffsetDateTime createdAt,
                           OffsetDateTime lastUsedAt, OffsetDateTime expiresAt,
                           Long usageCount) {
            this.keyId      = keyId;
            this.keyPrefix  = keyPrefix;
            this.name       = name;
            this.active     = active;
            this.createdAt  = createdAt;
            this.lastUsedAt = lastUsedAt;
            this.expiresAt  = expiresAt;
            this.usageCount = usageCount;
        }
    }

    public static class ErrorResponse {
        public String error;
        public String message;
        public ErrorResponse(String error, String message) {
            this.error   = error;
            this.message = message;
        }
    }
}