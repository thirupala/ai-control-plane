package com.decisionmesh.analytics;

import com.decisionmesh.analytics.CostAnalyticsService.CostAnalyticsDto;
import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * Analytics endpoints for the Dashboard and CostAnalytics pages.
 *
 * GET /api/analytics/cost
 *   → { totalCostUsd, avgCostPerIntent, costOverTime[], costByAdapter[] }
 *
 * All queries are tenant-scoped via the authenticated identity.
 */
@Path("/api/analytics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AnalyticsResource {

    @Inject SecurityIdentity      securityIdentity;
    @Inject CostAnalyticsService  costAnalyticsService;

    /**
     * GET /api/analytics/cost
     *
     * Used by:
     *   Dashboard.jsx      — KPI cards (totalCostUsd, avgCostPerIntent)
     *                        + area chart (costOverTime)
     *                        + bar chart  (costByAdapter)
     *   CostAnalytics.jsx  — full analytics page
     *
     * Optional query params (reserved for future filtering):
     *   period  — "7d" | "30d" | "90d" (ignored for now, always returns 30d)
     */
    @GET
    @Path("/cost")
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<CostAnalyticsDto> getCostAnalytics(
            @QueryParam("period") @DefaultValue("30d") String period) {

        UUID tenantId = resolveIdentity().tenantId();
        return costAnalyticsService.getAnalytics(tenantId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private AuthenticatedIdentity resolveIdentity() {
        AuthenticatedIdentity auth = securityIdentity.getCredential(AuthenticatedIdentity.class);
        if (auth == null)
            throw new NotAuthorizedException("Identity not resolved");
        return auth;
    }
}
