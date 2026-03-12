package com.decisionmesh.api;

import com.decisionmesh.application.service.ControlPlaneOrchestrator;
import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.domain.intent.Intent;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;
import io.quarkus.logging.Log;

import java.util.UUID;

import static io.micrometer.core.instrument.config.NamingConvention.identity;



@Path("api/intents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IntentResource {

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    ControlPlaneOrchestrator orchestrator;


    @Inject
    JsonWebToken jwt;

    @GET
    @Path("/auth/me")
    @Authenticated
    public AuthenticatedIdentity me() {
        return securityIdentity.getCredential(AuthenticatedIdentity.class);
    }


    @POST
    @RolesAllowed({"sys_admin","tenant_admin", "tenant_user"})
    public Uni<UUID> submit(Intent intent, @HeaderParam("Idempotency-Key") String idempotencyKey) {
        Log.info("Intent resource entry");

        if (intent == null) throw new BadRequestException("Intent body required");
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new BadRequestException("Missing Idempotency-Key header");

        UUID tenantId = getTenantFromToken();
        Log.infof("tenantId resolved: %s", tenantId);  // ← add

        return orchestrator.submit(intent, tenantId, idempotencyKey, "default")
                .onItem().invoke(id -> Log.infof("Orchestrator returned id: %s", id))  // ← add
                .onFailure().invoke(e -> Log.errorf(e, "Orchestrator failed"));

    }

    @GET
    @Path("/{intentId}")
    @RolesAllowed({"sys_admin","tenant_admin", "tenant_user"})
    public Uni<Intent> get(@PathParam("intentId") UUID intentId) {

        UUID tenantId = getTenantFromToken();

        return orchestrator.getById(tenantId, intentId)
                .onItem().ifNull().failWith(new NotFoundException());
    }

    private UUID getTenantFromToken() {
        UUID tenantId = securityIdentity.getAttribute("tenantId");
        if (tenantId == null) {
            throw new NotAuthorizedException("Missing tenantId");
        }
        return tenantId;
    }
}
