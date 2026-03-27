package com.decisionmesh.api;

import com.decisionmesh.application.service.ControlPlaneOrchestrator;
import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.domain.intent.Intent;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

@Path("api/intents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IntentResource {

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    ControlPlaneOrchestrator orchestrator;

    @GET
    @Path("/auth/me")
    @Authenticated
    public AuthenticatedIdentity me() {
        return securityIdentity.getCredential(AuthenticatedIdentity.class);
    }

    @POST
    @Blocking
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public UUID submit(Intent intent,
                            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        Log.infof(">>> INCOMING REQUEST: intentId=%s, idempotencyKey=%s, thread=%s",
                intent.getId(),
                idempotencyKey,
                Thread.currentThread().getName());
        if (intent == null)
            throw new BadRequestException("Intent body required");
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new BadRequestException("Missing Idempotency-Key header");

        AuthenticatedIdentity auth = getAuthenticatedIdentity();
        intent.setTenantId(auth.tenantId());
        intent.setUserId(auth.userId());
        Log.infof("Intent submission: tenant=%s, idempotency=%s", auth.tenantId(), idempotencyKey);

        return orchestrator.submit(
                intent,

                auth.tenantId(),
                idempotencyKey,
                intent.getIntentType()
        );
    }

    @GET
    @Path("/{intentId}")
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<Intent> get(@PathParam("intentId") UUID intentId) {
        AuthenticatedIdentity auth = getAuthenticatedIdentity();
        return orchestrator.getById(auth.tenantId(), intentId)
                .onItem().ifNull().failWith(new NotFoundException());
    }

    /**
     * Reads AuthenticatedIdentity from the credential — populated by IdentityAugmentor.
     * Never reads raw JWT claims directly.
     */
    private AuthenticatedIdentity getAuthenticatedIdentity() {
        AuthenticatedIdentity auth = securityIdentity.getCredential(AuthenticatedIdentity.class);
        if (auth == null) {
            throw new NotAuthorizedException("Identity not resolved — check IdentityAugmentor");
        }
        return auth;
    }
}