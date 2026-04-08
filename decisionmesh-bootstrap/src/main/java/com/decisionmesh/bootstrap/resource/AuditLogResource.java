package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.dto.AuditLogDto;
import com.decisionmesh.persistence.entity.AuditLogEntity;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.UUID;

@Path("/api/audit")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
public class AuditLogResource {

    @Inject JsonWebToken jwt;

    @GET
    @WithSession
    @NonBlocking
    public Uni<AuditLogDto.AuditPage> list(
            @QueryParam("page")   @DefaultValue("0")  int    page,
            @QueryParam("size")   @DefaultValue("50") int    size,
            @QueryParam("userId")                     String userId,
            @QueryParam("action")                     String action) {

        UUID tenantId    = tenantId();
        int  clampedSize = Math.min(Math.max(size, 1), 200);

        Uni<List<AuditLogEntity>> dataUni  =
                AuditLogEntity.findByTenant(tenantId, userId, action, page, clampedSize);
        Uni<Long> countUni =
                AuditLogEntity.countByTenant(tenantId, userId, action);

        return Uni.combine().all().unis(dataUni, countUni)
                .asTuple()
                .map(t -> new AuditLogDto.AuditPage(
                        t.getItem1().stream().map(AuditLogDto::from).toList(),
                        t.getItem2(),
                        page,
                        clampedSize));
    }

    private UUID tenantId() {
        String tid = jwt.getClaim("tenantId");
        if (tid == null || tid.isBlank()) throw new ForbiddenException("Missing tenantId in token");
        try { return UUID.fromString(tid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid tenantId format"); }
    }
}
