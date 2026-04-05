package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.application.service.ControlPlaneOrchestrator;
import com.decisionmesh.bootstrap.service.IntentService;
import com.decisionmesh.bootstrap.dto.IntentEventDto;
import com.decisionmesh.bootstrap.dto.IntentResponse;
import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.persistence.entity.IntentEntity;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/intents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IntentResource {

    @Inject SecurityIdentity         securityIdentity;
    @Inject ControlPlaneOrchestrator orchestrator;
    @Inject IntentService            intentService;

    // ─────────────────────────────────────────────────────────────
    // Identity
    // ─────────────────────────────────────────────────────────────

    @GET
    @Path("/auth/me")
    @Authenticated
    public AuthenticatedIdentity me() {
        return securityIdentity.getCredential(AuthenticatedIdentity.class);
    }

    // ─────────────────────────────────────────────────────────────
    // Submit Intent
    //
    // Returns UUID immediately after CREATED is persisted.
    // Remaining pipeline runs asynchronously in the background.
    //
    // @WithSession required: intentRepository.save() inside the
    // orchestrator's sync Phase 1 chain needs a Hibernate Reactive
    // session bound to the Vert.x duplicated context. Without it:
    //   "No current Quarkus vertx context found"
    // ─────────────────────────────────────────────────────────────

    @POST
    @NonBlocking
    @WithSession
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public Uni<UUID> submit(Intent intent,
                            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        if (intent == null)
            throw new BadRequestException("Intent body required");
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new BadRequestException("Missing Idempotency-Key header");

        AuthenticatedIdentity auth = resolveIdentity();
        intent.setTenantId(auth.tenantId());
        intent.setUserId(auth.userId());

        Log.infof("Intent submission: id=%s tenant=%s type=%s",
                intent.getId(), auth.tenantId(), intent.getIntentType());

        return orchestrator.submit(intent, auth.tenantId(), idempotencyKey, intent.getIntentType());
    }

    // ─────────────────────────────────────────────────────────────
    // Get Single Intent
    // ─────────────────────────────────────────────────────────────

    @GET
    @Path("/{intentId}")
    @WithSession
    @NonBlocking
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public Uni<IntentEntity> get(@PathParam("intentId") UUID intentId) {
        AuthenticatedIdentity auth = resolveIdentity();
        return intentService.getIntent(auth.tenantId(), intentId)
                .onItem().ifNull().failWith(
                        new NotFoundException("Intent not found: " + intentId));
    }

    // ─────────────────────────────────────────────────────────────
    // Get Intent Events
    //
    // BUG FIX: dropped when service layer was introduced.
    // ExecutionTimeline.jsx polls this every 5s:
    //   GET /api/intents/{id}/events  →  List<IntentEventDto>
    // Without it every poll returned 404, the timeline had no events
    // to render, and the UI showed CREATED/In progress permanently —
    // even after the pipeline reached SATISFIED in the database.
    // ─────────────────────────────────────────────────────────────

    @GET
    @Path("/{intentId}/events")
    @WithSession
    @NonBlocking
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public Uni<List<IntentEventDto>> getEvents(@PathParam("intentId") UUID intentId) {
        AuthenticatedIdentity auth = resolveIdentity();
        return intentService.getIntentEvents(auth.tenantId(), intentId);
    }

    // ─────────────────────────────────────────────────────────────
    // List Intents (Paginated)
    // ─────────────────────────────────────────────────────────────

    @GET
    @WithSession
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public Uni<IntentResponse> list(
            @QueryParam("page")  @DefaultValue("0")              int    page,
            @QueryParam("size")  @DefaultValue("20")             int    size,
            @QueryParam("sort")  @DefaultValue("createdAt,desc") String sort,
            @QueryParam("phase")                                 String phase) {

        AuthenticatedIdentity auth = resolveIdentity();

        String[] parts   = sort.split(",", 2);
        String sortField = parts[0].trim();
        String sortDir   = parts.length > 1 ? parts[1].trim() : "desc";

        int clampedSize = Math.min(Math.max(size, 1), 100);
        String phaseFilter = (phase != null && !phase.isBlank()) ? phase.toUpperCase() : null;

        return intentService.getIntents(
                auth.tenantId(), phaseFilter, sortField, sortDir, page, clampedSize);
    }

    // ─────────────────────────────────────────────────────────────
    // Delete Intent
    // ─────────────────────────────────────────────────────────────

    @DELETE
    @Path("/{intentId}")
    @WithTransaction
    @NonBlocking
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public Uni<Response> delete(@PathParam("intentId") UUID intentId) {
        AuthenticatedIdentity auth = resolveIdentity();
        return intentService.deleteIntent(auth.tenantId(), intentId)
                .map(deleted -> deleted
                        ? Response.noContent().build()
                        : Response.status(Response.Status.NOT_FOUND).build())
                .onFailure().recoverWithItem(ex -> Response.serverError().build());
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private AuthenticatedIdentity resolveIdentity() {
        AuthenticatedIdentity auth =
                securityIdentity.getCredential(AuthenticatedIdentity.class);
        if (auth == null) {
            throw new NotAuthorizedException(
                    "Identity not resolved — check IdentityAugmentor");
        }
        return auth;
    }
}