package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.dto.PolicyDto;
import com.decisionmesh.bootstrap.dto.PolicyDto.SavePolicyRequest;
import com.decisionmesh.bootstrap.service.AuditService;
import com.decisionmesh.bootstrap.service.PolicyService;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
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

@Path("/api/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
public class PolicyResource {

    @Inject JsonWebToken  jwt;
    @Inject PolicyService policyService;
    @Inject AuditService  auditService;

    @GET
    @WithSession
    @NonBlocking
    public Uni<List<PolicyDto>> list() {
        return policyService.list(tenantId());
    }

    @POST
    @WithTransaction
    @NonBlocking
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public Uni<Response> create(SavePolicyRequest body) {
        if (body == null || body.name == null || body.name.isBlank())
            throw new BadRequestException("Policy name is required");

        UUID tenantId = tenantId();
        UUID userId   = userId();

        return policyService.create(tenantId, body)
                .map(dto -> {
                    auditService.log(tenantId, userId.toString(),
                            AuditService.ACTION_POLICY_CREATED,
                            "POLICY", dto.policyId, "Created: " + dto.name);
                    return Response.status(Response.Status.CREATED).entity(dto).build();
                });
    }

    @PUT
    @Path("/{id}")
    @WithTransaction
    @NonBlocking
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public Uni<PolicyDto> update(@PathParam("id") UUID id, SavePolicyRequest body) {
        if (body == null || body.name == null || body.name.isBlank())
            throw new BadRequestException("Policy name is required");

        UUID tenantId = tenantId();
        UUID userId   = userId();

        return policyService.update(tenantId, id, body)
                .invoke(dto -> auditService.log(tenantId, userId.toString(),
                        AuditService.ACTION_POLICY_UPDATED,
                        "POLICY", id, "Updated: " + dto.name));
    }

    @DELETE
    @Path("/{id}")
    @WithTransaction
    @NonBlocking
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public Uni<Response> delete(@PathParam("id") UUID id) {
        UUID tenantId = tenantId();
        UUID userId   = userId();

        return policyService.delete(tenantId, id)
                .invoke(() -> auditService.log(tenantId, userId.toString(),
                        AuditService.ACTION_POLICY_DELETED,
                        "POLICY", id, null))
                .replaceWith(Response.noContent().build());
    }

    private UUID tenantId() {
        String tid = jwt.getClaim("tenantId");
        if (tid == null || tid.isBlank()) throw new ForbiddenException("Missing tenantId in token");
        try { return UUID.fromString(tid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid tenantId format"); }
    }

    private UUID userId() {
        String uid = jwt.getClaim("userId");
        if (uid == null || uid.isBlank()) throw new ForbiddenException("Missing userId in token");
        try { return UUID.fromString(uid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid userId format"); }
    }
}
