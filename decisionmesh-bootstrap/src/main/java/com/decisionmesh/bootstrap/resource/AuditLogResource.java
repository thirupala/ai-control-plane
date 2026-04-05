package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.dto.AuditLogDto;
import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.persistence.entity.AuditLogEntity;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * REST resource for the audit log.
 *
 * Endpoint consumed by AuditLog.jsx via listAudit() in api.js:
 *   GET /api/audit?page=0&size=50&userId=...&action=...
 *   → { content:[...], totalElements, totalPages, size, number }
 *
 * Filters (both optional, ILIKE partial match):
 *   userId → matched against user_id VARCHAR column
 *   action → matched against action VARCHAR column
 */
@Path("/api/audit")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
public class AuditLogResource {

    @Inject
    SecurityIdentity securityIdentity;

    @GET
    @WithSession
    @NonBlocking
    public Uni<AuditLogDto.AuditPage> list(
            @QueryParam("page")   @DefaultValue("0")  int    page,
            @QueryParam("size")   @DefaultValue("50") int    size,
            @QueryParam("userId")                     String userId,
            @QueryParam("action")                     String action) {

        UUID tenantId    = resolveIdentity().tenantId();
        int  clampedSize = Math.min(Math.max(size, 1), 200);

        Uni<List<AuditLogEntity>> dataUni  =
                AuditLogEntity.findByTenant(tenantId, userId, action, page, clampedSize);
        Uni<Long>                 countUni =
                AuditLogEntity.countByTenant(tenantId, userId, action);

        return Uni.combine().all().unis(dataUni, countUni)
                .asTuple()
                .map(t -> new AuditLogDto.AuditPage(
                        t.getItem1().stream().map(AuditLogDto::from).toList(),
                        t.getItem2(),
                        page,
                        clampedSize));
    }

    private AuthenticatedIdentity resolveIdentity() {
        AuthenticatedIdentity auth =
                securityIdentity.getCredential(AuthenticatedIdentity.class);
        if (auth == null) throw new NotAuthorizedException(
                "Identity not resolved — check IdentityAugmentor");
        return auth;
    }
}