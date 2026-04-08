package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.service.OnboardingService;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import java.net.URI;
import java.util.Map;

@Path("/api/onboard")
public class OnboardingResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    JsonWebToken jwt; // Direct access to Keycloak claims

    @Inject
    OnboardingService onboardingService;

    @GET
    @Path("/me")
    @Authenticated
    public Uni<Response> me() {

        String externalId = identity.getPrincipal().getName();
        String email      = jwt.getClaim("email");
        String name       = jwt.getClaim("name");

        if (externalId == null || externalId.isBlank())
            return Uni.createFrom().item(Response.status(Response.Status.UNAUTHORIZED).build());

        return onboardingService.provisionNewUser(externalId, email, name)
                .map(ignored -> Response.ok(Map.of(
                        "externalId", externalId,
                        "email",      email  != null ? email : "",
                        "name",       name   != null ? name  : ""
                )).build())
                .onFailure().invoke(e ->
                        Log.errorf("Provisioning failed for %s: %s", externalId, e.getMessage())
                )
                .onFailure().recoverWithItem(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR).build()
                );
    }
}