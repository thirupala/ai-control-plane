package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.common.dto.BrandingRequest;
import com.decisionmesh.persistence.repository.OrgRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

@Path("/api/org")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class OrgBrandingResource {

    @Inject
    OrgRepository orgRepository;

    @Inject
    JsonWebToken jwt;

    // -------------------------
    // UPDATE BRANDING
    // -------------------------
    @PATCH
    @Path("/branding")
    @WithTransaction
    public Uni<Response> updateBranding(BrandingRequest dto) {

        return getTenantId()
                .onItem().transformToUni(tenantId ->
                        orgRepository.upsertBranding(tenantId, dto)
                )
                .replaceWith(Response.ok().build());
    }

    // -------------------------
    // GET BRANDING
    // -------------------------
    @GET
    @Path("/branding")
    @WithTransaction
    public Uni<Response> getBranding() {

        return getTenantId()
                .onItem().transformToUni(tenantId ->
                        orgRepository.findBrandingByTenantId(tenantId)
                )
                .onItem().transform(branding ->
                        branding != null
                                ? Response.ok(branding).build()
                                : Response.status(Response.Status.NOT_FOUND).build()
                );
    }

    // -------------------------
    // TENANT RESOLUTION (SINGLE SOURCE OF TRUTH)
    // -------------------------
    private Uni<UUID> getTenantId() {

        return Uni.createFrom().item(() -> {

            String tid = jwt.getClaim("tenantId");

            if (tid == null || tid.isBlank()) {
                throw new ForbiddenException("Missing tenantId in token");
            }

            try {
                return UUID.fromString(tid);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid tenantId format");
            }
        });
    }
}