package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.contracts.security.entity.UserOrganizationEntity;
import com.decisionmesh.contracts.security.service.UserOrganizationService;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/members")
@Produces(MediaType.APPLICATION_JSON)
public class MemberResource {

    @Inject
    UserOrganizationService service;
    @Inject
    SecurityIdentity identity;

    @GET
    public Uni<List<UserOrganizationEntity>> list() {
        AuthenticatedIdentity auth =
                identity.getCredential(AuthenticatedIdentity.class);

        UUID userId = auth.userId();

        return service.findAllByUserId(userId);
    }

    @DELETE
    @Path("/{userId}")
    public Uni<Void> remove(@PathParam("userId") UUID userId) {
        // Simplified: orgId needed in real system
        return service.deactivateMembership(userId, null);
    }

    @PATCH
    @Path("/{userId}/role")
    public Uni<UserOrganizationEntity> updateRole(@PathParam("userId") UUID userId,
                                                  Map<String, String> body) {

        // Simplified example
        return service.findByUserAndTenant(userId, resolveTenant())
                .invoke(m -> m.role = body.get("role"))
                .flatMap(service.repository::persist);
    }

    private UUID resolveTenant() {
        return identity.getCredential(AuthenticatedIdentity.class).tenantId();
    }
}