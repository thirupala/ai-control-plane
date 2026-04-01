package com.decisionmesh.bootstrap.api;

import com.decisionmesh.application.service.ControlPlaneOrchestrator;
import com.decisionmesh.bootstrap.dto.IntentSummaryDto;
import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.persistence.repository.IntentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/intents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IntentResource {

    @Inject SecurityIdentity         securityIdentity;
    @Inject ControlPlaneOrchestrator orchestrator;
    @Inject IntentRepository         intentRepository;
    @Inject ObjectMapper             mapper;

    // ── Identity helper ───────────────────────────────────────────────────────

    @GET
    @Path("/auth/me")
    @Authenticated
    public AuthenticatedIdentity me() {
        return securityIdentity.getCredential(AuthenticatedIdentity.class);
    }

    // ── Submit intent ─────────────────────────────────────────────────────────

    @POST
    @NonBlocking
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<UUID> submit(Intent intent,
                            @HeaderParam("Idempotency-Key") String idempotencyKey) {

        if (intent == null)
            throw new BadRequestException("Intent body required");
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new BadRequestException("Missing Idempotency-Key header");

        AuthenticatedIdentity auth = resolveIdentity();
        intent.setTenantId(auth.tenantId());
        intent.setUserId(auth.userId());

        Log.infof("Intent submission: id=%s tenant=%s type=%s idempotency=%s thread=%s",
                intent.getId(), auth.tenantId(), intent.getIntentType(),
                idempotencyKey, Thread.currentThread().getName());

        return orchestrator.submit(intent, auth.tenantId(), idempotencyKey, intent.getIntentType());
    }

    // ── Get single intent ─────────────────────────────────────────────────────

    @GET
    @Path("/{intentId}")
    @NonBlocking
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<Intent> get(@PathParam("intentId") UUID intentId) {
        AuthenticatedIdentity auth = resolveIdentity();
        return orchestrator.getById(auth.tenantId(), intentId)
                .onItem().ifNull().failWith(new NotFoundException("Intent not found: " + intentId));
    }

    // ── List intents — paginated ───────────────────────────────────────────────
    //
    // Used by:
    //   Dashboard.jsx  — size=8, sort=createdAt,desc
    //   IntentsTable   — size=20, phase filter, sort controls
    //
    // Response: { content:[...], totalElements:N, totalPages:N, size:N, number:N }
    // Each item: id, intentType, phase, satisfactionState, budget{spentUsd}, createdAt, version

    @GET
    @WithSession
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public Uni<IntentSummaryDto.IntentPageResponse> list(
            @QueryParam("page")  @DefaultValue("0")              int    page,
            @QueryParam("size")  @DefaultValue("20")             int    size,
            @QueryParam("sort")  @DefaultValue("createdAt,desc") String sort,
            @QueryParam("phase")                                 String phase) {

        AuthenticatedIdentity auth     = resolveIdentity();
        UUID                  tenantId = auth.tenantId();

        String[] parts     = sort.split(",", 2);
        String   sortField = parts[0].trim();
        String   sortDir   = parts.length > 1 ? parts[1].trim() : "desc";

        int    clampedSize  = Math.min(Math.max(size, 1), 100);
        String phaseFilter  = (phase != null && !phase.isBlank()) ? phase.toUpperCase() : null;

        Uni<Long> countUni = phaseFilter != null
                ? intentRepository.countByTenantAndPhase(tenantId, phaseFilter)
                : intentRepository.countByTenant(tenantId);

        Uni<List<com.decisionmesh.persistence.entity.IntentEntity>> listUni =
                intentRepository.findPageByTenant(tenantId, phaseFilter, sortField, sortDir, page, clampedSize);

        return Uni.combine().all().unis(countUni, listUni).asTuple()
                .map(tuple -> {
                    long total      = tuple.getItem1();
                    var  entities   = tuple.getItem2();
                    int  totalPages = (int) Math.ceil((double) total / clampedSize);

                    var content = entities.stream()
                            .map(e -> IntentSummaryDto.from(e, mapper))
                            .toList();

                    return new IntentSummaryDto.IntentPageResponse(content, total, totalPages, clampedSize, page);
                });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private AuthenticatedIdentity resolveIdentity() {
        AuthenticatedIdentity auth = securityIdentity.getCredential(AuthenticatedIdentity.class);
        if (auth == null)
            throw new NotAuthorizedException("Identity not resolved — check IdentityAugmentor");
        return auth;
    }
}
