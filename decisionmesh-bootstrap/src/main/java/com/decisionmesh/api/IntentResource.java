package com.decisionmesh.api;

import com.decisionmesh.application.service.ControlPlaneOrchestrator;
import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.domain.intent.Intent;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

@Path("/api/intents")
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

    /**
     * Submit a new intent.
     *
     * @Blocking removed — orchestrator.submit() returns Uni<UUID> which
     * RESTEasy Reactive handles natively without a worker thread dispatch.
     *
     * Identity binding (tenantId, userId) is done here in the resource — the
     * correct place since the security context is available. The orchestrator
     * receives a fully-populated intent and does not re-set identity fields.
     */
    @POST
    @NonBlocking                    // ← keeps execution on event loop thread
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<UUID> submit(Intent intent,
                            @HeaderParam("Idempotency-Key") String idempotencyKey) {

        // Null check before any dereference
        if (intent == null)
            throw new BadRequestException("Intent body required");
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new BadRequestException("Missing Idempotency-Key header");

        AuthenticatedIdentity auth = resolveIdentity();

        // Bind server-managed identity fields — done once here, not repeated in orchestrator
        intent.setTenantId(auth.tenantId());
        intent.setUserId(auth.userId());

        Log.infof("Intent submission: id=%s, tenant=%s, type=%s, idempotency=%s, thread=%s",
                intent.getId(),
                auth.tenantId(),
                intent.getIntentType(),
                idempotencyKey,
                Thread.currentThread().getName());

        return orchestrator.submit(
                intent,
                auth.tenantId(),
                idempotencyKey,
                intent.getIntentType()
        );
    }

    @GET
    @Path("/{intentId}")
    @NonBlocking                    // ← required — same reason as submit()
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<Intent> get(@PathParam("intentId") UUID intentId) {
        AuthenticatedIdentity auth = resolveIdentity();
        return orchestrator.getById(auth.tenantId(), intentId)
                .onItem().ifNull().failWith(new NotFoundException(
                        "Intent not found: " + intentId));
    }

    /**
     * Resolves AuthenticatedIdentity from the security credential.
     * Populated by IdentityAugmentor — never reads raw JWT claims directly.
     * Throws 401 if the augmentor failed to attach the credential.
     */
    private AuthenticatedIdentity resolveIdentity() {
        AuthenticatedIdentity auth = securityIdentity.getCredential(AuthenticatedIdentity.class);
        if (auth == null) {
            throw new NotAuthorizedException("Identity not resolved — check IdentityAugmentor");
        }
        return auth;
    }
}