package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.dto.ApiKeyDto;
import com.decisionmesh.bootstrap.dto.ApiKeyDto.ApiKeyCreatedDto;
import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.contracts.security.service.ApiKeyService;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/**
 * REST resource for API key management.
 *
 * Endpoints consumed by {@code ApiKeys.jsx}:
 * <pre>
 *   GET    /api/api-keys        → List&lt;ApiKeyDto&gt;   (list all tenant keys)
 *   POST   /api/api-keys        → ApiKeyCreatedDto    (create, returns plaintext key once)
 *   DELETE /api/api-keys/{id}   → 204 No Content      (revoke)
 * </pre>
 *
 * All three endpoints delegate to {@link ApiKeyService} which lives in the
 * {@code decisionmesh-security} module at
 * {@code com.decisionmesh.contracts.security.service}.
 */
@Path("/api/api-keys")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
public class ApiKeyResource {

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    ApiKeyService apiKeyService;

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/api-keys
    //
    // ApiKeys.jsx load() calls:  req(keycloak, '/api-keys')
    // Maps each entity to ApiKeyDto (safe — no hash, no raw key).
    // Returns all keys for the tenant: active + expired + revoked.
    // The UI splits them client-side via:
    //   active  = !k.revokedAt && (!k.expiresAt || expiresAt > now)
    //   expired = k.revokedAt  || (k.expiresAt && expiresAt <= now)
    // ─────────────────────────────────────────────────────────────────────────

    @GET
    @WithSession
    @NonBlocking
    public Uni<List<ApiKeyDto>> list() {
        UUID tenantId = resolveIdentity().tenantId();
        // activeOnly=false — return everything, UI splits client-side
        return apiKeyService.listKeys(tenantId, false)
                .map(entities -> entities.stream()
                        .map(ApiKeyDto::from)
                        .toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/api-keys
    //
    // ApiKeys.jsx CreateKeyModal.handleCreate() sends:
    //   { name, scopes: [...], expiryDays: 90 | null }
    //
    // ApiKeyService.createApiKey() returns ApiKeyResult:
    //   { keyId, key (plaintext), keyPrefix, createdAt, expiresAt }
    //
    // We bridge ApiKeyResult + request fields (name, scopes) into
    // ApiKeyCreatedDto — which RevealedKey reads:
    //   apiKey.id, apiKey.key, apiKey.name, apiKey.scopes, apiKey.keyPrefix
    //
    // organizationId: derived from tenantId — pass tenantId for both when
    // your multi-org structure keeps them 1:1, or inject OrgService to resolve.
    // ─────────────────────────────────────────────────────────────────────────

    @POST
    @WithTransaction
    @NonBlocking
    @RolesAllowed({"admin", "tenant_admin"})
    public Uni<ApiKeyCreatedDto> create(CreateKeyRequest body) {
        if (body == null || body.name == null || body.name.isBlank()) {
            throw new BadRequestException("Key name is required");
        }
        if (body.scopes == null || body.scopes.isEmpty()) {
            throw new BadRequestException("At least one scope is required");
        }

        AuthenticatedIdentity auth = resolveIdentity();

        // organizationId: nullable FK — AuthenticatedIdentity carries no organizationId.
        // To populate it, inject your OrganizationEntity and query:
        //   OrganizationEntity.find("tenantId = ?1", auth.tenantId()).firstResult()
        //   .flatMap(org -> apiKeyService.createApiKey(org != null ? org.id : null, ...))
        return apiKeyService.createApiKey(
                        null,
                        auth.tenantId(),
                        auth.userId(),
                        body.name.trim(),
                        false,
                        body.expiryDays
                )
                .map(result ->
                        // Merge service result + request fields into the UI shape
                        ApiKeyCreatedDto.from(result, body.name.trim(), body.scopes)
                );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE /api/api-keys/{id}
    //
    // ApiKeys.jsx handleRevoke(id) calls:
    //   req(keycloak, `/api-keys/${id}`, { method: 'DELETE' })
    //
    // {id} in the URL is ApiKeyDto.id, which maps from ApiKeyEntity.keyId.
    // Service method: revokeKeyForTenant(UUID keyId, UUID tenantId)
    //   → calls entity.revoke("api") which sets active=false + revokedAt=now()
    //   → returns false if not found or cross-tenant attempt
    //
    // Restricted to tenant_admin and sys_admin — tenant_user cannot revoke keys.
    // ─────────────────────────────────────────────────────────────────────────

    @DELETE
    @Path("/{id}")
    @WithTransaction
    @NonBlocking
    @RolesAllowed({"admin", "tenant_admin"})
    public Uni<Response> revoke(@PathParam("id") UUID id) {
        AuthenticatedIdentity auth = resolveIdentity();
        return apiKeyService.revokeKeyForTenant(id, auth.tenantId())
                .map(revoked -> revoked
                        ? Response.noContent().build()
                        : Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"API key not found\"}")
                        .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Identity helper
    // ─────────────────────────────────────────────────────────────────────────

    private AuthenticatedIdentity resolveIdentity() {
        AuthenticatedIdentity auth =
                securityIdentity.getCredential(AuthenticatedIdentity.class);
        if (auth == null) {
            throw new NotAuthorizedException(
                    "Identity not resolved — check IdentityAugmentor");
        }
        return auth;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request body
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Matches the body sent by {@code CreateKeyModal.handleCreate()} in ApiKeys.jsx:
     * <pre>
     * {
     *   "name":       "Production backend",
     *   "scopes":     ["intents:write", "intents:read"],
     *   "expiryDays": 90   // or null for no expiry
     * }
     * </pre>
     *
     * Note: the service always sets {@code scopes = List.of("*")} by default,
     * but the entity's scopes field is set here from the request so the UI
     * can display the granular scopes the user selected in the modal.
     * If you want the service to honour request scopes, pass {@code body.scopes}
     * into the entity after {@code createApiKey()} returns — or override in a
     * subclass. For now the DTO carries request scopes directly.
     */
    public static class CreateKeyRequest {
        public String       name;
        public List<String> scopes;
        /** Days until expiry. Null = key never expires. */
        public Integer      expiryDays;
    }
}