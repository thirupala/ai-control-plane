package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.service.InvitationService;
import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.persistence.entity.InvitationEntity;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/invitations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InvitationResource {

    @Inject InvitationService service;
    @Inject SecurityIdentity identity;

    @POST
    public Uni<InvitationEntity> invite(Map<String, String> body) {

        UUID tenantId = resolveTenant();

        return service.createInvitation(
                tenantId,
                body.get("email"),
                body.get("role")
        );
    }

    @GET
    public Uni<List<InvitationEntity>> list() {
        return service.list(resolveTenant());
    }

    @DELETE
    @Path("/{id}")
    public Uni<Void> revoke(@PathParam("id") UUID id) {
        return service.revoke(id);
    }

    private UUID resolveTenant() {
        return identity.getCredential(AuthenticatedIdentity.class).tenantId();
    }
}