package com.decisionmesh.bootstrap.api;


import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/**
 * Called by the React UI on every authenticated session start.
 * Idempotent — safe to call multiple times.
 *
 * The UI calls this in AppWrapper after Keycloak confirms authentication:
 *   POST /api/provision
 *
 * This ensures every new user gets their org + project + 500 credits
 * the first time they log in, without requiring a separate Keycloak event hook.
 */
@Path("/api/provision")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class TenantProvisioningResource {

    @Inject TenantProvisioningService provisioningService;
    @Inject SecurityIdentity           identity;

    @POST
    @RolesAllowed({"user", "admin"})
    public Uni<Response> provision() {
        UUID   tenantId = tenantId();
        String name     = identity.getAttribute("org_name");
        String email    = identity.getAttribute("email");

        if (name == null) {
            // Fall back to preferred_username as org name for solo accounts
            name = identity.getAttribute("preferred_username");
        }

        return provisioningService.provisionIfAbsent(tenantId, name, email)
                .replaceWith(Response.noContent().build())
                .onFailure().recoverWithItem(e -> {
                    // Non-fatal — UI still works without provisioning
                    return Response.ok().build();
                });
    }

    private UUID tenantId() {
        String claim = identity.getAttribute("tenant_id");
        return claim != null
                ? UUID.fromString(claim)
                : UUID.fromString(identity.getPrincipal().getName());
    }
}
