package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.application.service.ControlPlaneOrchestrator;
import com.decisionmesh.bootstrap.service.IntentService;
import com.decisionmesh.bootstrap.dto.IntentEventDto;
import com.decisionmesh.bootstrap.dto.IntentResponse;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.persistence.entity.IntentEntity;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
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

@Path("/api/intents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "tenant_admin", "tenant_user"})
public class IntentResource {

    @Inject JsonWebToken             jwt;
    @Inject ControlPlaneOrchestrator orchestrator;
    @Inject IntentService            intentService;

    // ── Submit ────────────────────────────────────────────────────────────────

    @POST
    @NonBlocking
    @WithSession
    public Uni<UUID> submit(Intent intent,
                            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        if (intent == null)
            throw new BadRequestException("Intent body required");
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new BadRequestException("Missing Idempotency-Key header");

        UUID tenantId = tenantId();
        UUID userId   = userId();

        intent.setTenantId(tenantId);
        intent.setUserId(userId);

        Log.infof("Intent submission: id=%s tenant=%s type=%s",
                intent.getId(), tenantId, intent.getIntentType());

        return orchestrator.submit(intent, tenantId, idempotencyKey, intent.getIntentType());
    }

    // ── Get single ────────────────────────────────────────────────────────────

    @GET
    @Path("/{intentId}")
    @WithSession
    @NonBlocking
    public Uni<IntentEntity> get(@PathParam("intentId") UUID intentId) {
        return intentService.getIntent(tenantId(), intentId)
                .onItem().ifNull().failWith(
                        new NotFoundException("Intent not found: " + intentId));
    }

    // ── Get events ────────────────────────────────────────────────────────────

    @GET
    @Path("/{intentId}/events")
    @WithSession
    @NonBlocking
    public Uni<List<IntentEventDto>> getEvents(@PathParam("intentId") UUID intentId) {
        return intentService.getIntentEvents(tenantId(), intentId);
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GET
    @WithSession
    public Uni<IntentResponse> list(
            @QueryParam("page")  @DefaultValue("0")              int    page,
            @QueryParam("size")  @DefaultValue("20")             int    size,
            @QueryParam("sort")  @DefaultValue("createdAt,desc") String sort,
            @QueryParam("phase")                                 String phase) {

        String[] parts    = sort.split(",", 2);
        String sortField  = parts[0].trim();
        String sortDir    = parts.length > 1 ? parts[1].trim() : "desc";
        int    clampedSize = Math.min(Math.max(size, 1), 100);
        String phaseFilter = (phase != null && !phase.isBlank()) ? phase.toUpperCase() : null;

        return intentService.getIntents(
                tenantId(), phaseFilter, sortField, sortDir, page, clampedSize);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @DELETE
    @Path("/{intentId}")
    @WithTransaction
    @NonBlocking
    public Uni<Response> delete(@PathParam("intentId") UUID intentId) {
        return intentService.deleteIntent(tenantId(), intentId)
                .map(deleted -> deleted
                        ? Response.noContent().build()
                        : Response.status(Response.Status.NOT_FOUND).build())
                .onFailure().recoverWithItem(ex -> Response.serverError().build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID tenantId() {
        String tid = jwt.getClaim("tenantId");
        if (tid == null || tid.isBlank()) throw new ForbiddenException("Missing tenantId in token");
        try { return UUID.fromString(tid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid tenantId format"); }
    }

    // userId is written to Keycloak by OnboardingService.writeKeycloakTenantId()
    // and mapped via the 'userId' User Attribute mapper on control-plane-web-dedicated scope
    private UUID userId() {
        String uid = jwt.getClaim("userId");
        if (uid == null || uid.isBlank()) throw new ForbiddenException("Missing userId in token");
        try { return UUID.fromString(uid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid userId format"); }
    }
}
