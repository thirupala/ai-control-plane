package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.service.CostAnalyticsService;
import com.decisionmesh.bootstrap.service.CostAnalyticsService.CostAnalyticsDto;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

@Path("/api/analytics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "tenant_admin", "tenant_user"})
public class AnalyticsResource {

    @Inject JsonWebToken                             jwt;
    @Inject
    CostAnalyticsService costAnalyticsService;

    @GET
    @Path("/cost")
    public Uni<CostAnalyticsDto> getCostAnalytics(
            @QueryParam("period") @DefaultValue("30d") String period) {
        return costAnalyticsService.getAnalytics(tenantId());
    }

    private UUID tenantId() {
        String tid = jwt.getClaim("tenantId");
        if (tid == null || tid.isBlank()) throw new ForbiddenException("Missing tenantId in token");
        try { return UUID.fromString(tid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid tenantId format"); }
    }
}