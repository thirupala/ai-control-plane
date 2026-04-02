package com.decisionmesh.llm.resource;

import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.llm.service.AdapterPerformanceService;
import com.decisionmesh.llm.service.AdapterService;
import com.decisionmesh.persistence.entity.AdapterEntity;
import com.decisionmesh.persistence.entity.AdapterPerformanceEntity;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/adapters")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AdapterResource {

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    AdapterService service;

    @Inject
    AdapterPerformanceService adapterPerformanceService;

    // ─────────────────────────────────────────────
    // GET /api/adapters
    // ─────────────────────────────────────────────
    @GET
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<List<AdapterEntity>> list() {
        return service.list(resolveIdentity().tenantId());
    }

    // ─────────────────────────────────────────────
    // POST /api/adapters
    // ─────────────────────────────────────────────
    @POST
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<AdapterEntity> create(AdapterEntity adapterEntity) {
        return service.create(resolveIdentity().tenantId(), adapterEntity);
    }

    // ─────────────────────────────────────────────
    // PUT /api/adapters/{id}
    // ─────────────────────────────────────────────
    @PUT
    @Path("/{id}")
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<AdapterEntity> update(@PathParam("id") UUID id, AdapterEntity adapterEntity) {
        return service.update(resolveIdentity().tenantId(), id, adapterEntity);
    }

    // ─────────────────────────────────────────────
    // PATCH /api/adapters/{id}/status
    // ─────────────────────────────────────────────
    @PATCH
    @Path("/{id}/status")
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<AdapterEntity> toggle(@PathParam("id") UUID id,
                                     Map<String, Boolean> body) {
        Boolean isActive = body.get("isActive");
        if (isActive == null) {
            throw new BadRequestException("Missing 'isActive' field");
        }
        return service.toggle(resolveIdentity().tenantId(), id, isActive);
    }

    // ─────────────────────────────────────────────
    // DELETE /api/adapters/{id}
    // ─────────────────────────────────────────────
    @DELETE
    @Path("/{id}")
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<Void> delete(@PathParam("id") UUID id) {
        return service.delete(resolveIdentity().tenantId(), id);
    }

    // ─────────────────────────────────────────────
    // GET /api/adapters/{id}/performance
    // ─────────────────────────────────────────────
    @GET
    @Path("/{id}/performance")
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<AdapterPerformanceEntity> performance(@PathParam("id") UUID id) {
        return adapterPerformanceService.get(resolveIdentity().tenantId(), id);
    }

    // ─────────────────────────────────────────────
    // Identity helper — mirrors IntentResource.resolveIdentity()
    // ─────────────────────────────────────────────

    /**
     * Extracts the {@link AuthenticatedIdentity} credential attached by
     * {@code IdentityAugmentor} during the OIDC token validation phase.
     *
     * <p>The credential carries the real {@code tenantId} parsed from the JWT
     * by {@code OidcClaimsNormalizer}, ensuring the correct tenant row is used
     * for every database operation and preventing cross-tenant data leakage.
     *
     * @throws NotAuthorizedException if the augmentor did not attach the
     *         credential — indicates a misconfigured Keycloak client or a
     *         missing {@code OidcClaimsNormalizer} for the token's issuer.
     */
    private AuthenticatedIdentity resolveIdentity() {
        AuthenticatedIdentity auth = securityIdentity.getCredential(AuthenticatedIdentity.class);
        if (auth == null) {
            throw new NotAuthorizedException(
                    "AuthenticatedIdentity not resolved — check IdentityAugmentor and OidcClaimsNormalizer");
        }
        return auth;
    }
}