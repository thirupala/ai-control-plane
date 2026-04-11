package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.dto.ApiKeyDto;
import com.decisionmesh.bootstrap.dto.ApiKeyDto.ApiKeyCreatedDto;
import com.decisionmesh.contracts.security.entity.OrganizationEntity;
import com.decisionmesh.contracts.security.service.ApiKeyService;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.UUID;

/**
 * REST resource for API key management.
 */
@Path("/api/api-keys")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApiKeyResource {

    @Inject JsonWebToken  jwt;
    @Inject ApiKeyService apiKeyService;

    // ── GET /api/api-keys ─────────────────────────────────────────────────────

    @GET
    @WithSession
    @NonBlocking
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<List<ApiKeyDto>> list() {
        return apiKeyService.listKeys(tenantId(), false)
                .map(entities -> entities.stream()
                        .map(ApiKeyDto::from)
                        .toList());
    }

    // ── POST /api/api-keys ────────────────────────────────────────────────────

    @POST
    @WithTransaction
    @NonBlocking
    @RolesAllowed({"sys_admin", "tenant_user"})
    public Uni<ApiKeyCreatedDto> create(CreateKeyRequest body) {
        if (body == null || body.name == null || body.name.isBlank())
            throw new BadRequestException("Key name is required");

        UUID tenantId = tenantId();
        UUID userId   = userId();

        return resolveDefaultOrganizationId(tenantId)
                .flatMap(orgId -> apiKeyService.createApiKey(
                        orgId,
                        tenantId,
                        userId,
                        body.name.trim(),
                        false,
                        body.expiryDays
                ))
                .map(result -> ApiKeyCreatedDto.from(result, body.name.trim(), body.scopes));
    }

    // ── DELETE /api/api-keys/{id} ─────────────────────────────────────────────

    @DELETE
    @Path("/{id}")
    @WithTransaction
    @NonBlocking
    @RolesAllowed({"sys_admin", "tenant_admin"})
    public Uni<Response> revoke(@PathParam("id") UUID id) {
        return apiKeyService.revokeKeyForTenant(id, tenantId())
                .map(revoked -> revoked
                        ? Response.noContent().build()
                        : Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"API key not found or access denied\"}")
                        .build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID tenantId() {
        String tid = jwt.getClaim("tenantId");
        if (tid == null || tid.isBlank()) throw new ForbiddenException("Missing tenantId in token");
        try { return UUID.fromString(tid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid tenantId format"); }
    }

    private UUID userId() {
        String uid = jwt.getClaim("sub");
        if (uid == null || uid.isBlank()) throw new ForbiddenException("Missing userId in token");
        try { return UUID.fromString(uid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid userId format"); }
    }

    private Uni<UUID> resolveDefaultOrganizationId(UUID tenantId) {
        return OrganizationEntity
                .<OrganizationEntity>find("tenantId = ?1 order by createdAt asc", tenantId)
                .firstResult()
                .map(org -> {
                    if (org == null)
                        throw new NotFoundException("No organization found for tenant");
                    return org.id;
                });
    }

    // ── Request DTO ───────────────────────────────────────────────────────────

    public static class CreateKeyRequest {
        public String       name;
        public List<String> scopes;
        public Integer      expiryDays;
    }
}