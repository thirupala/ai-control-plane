package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.service.OnboardingService;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Map;

@Path("/api/onboard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OnboardingResource {

    @Inject SecurityIdentity  identity;
    @Inject JsonWebToken      jwt;
    @Inject OnboardingService onboardingService;

    // -------------------------------------------------------------------------
    // GET /api/onboard/me
    // Called on every login. Ensures user exists in DB.
    // Does NOT create a tenant — that happens in /setup-tenant.
    // Returns tenantId if already set up (so UI knows whether to show onboarding).
    // -------------------------------------------------------------------------
    @GET
    @Path("/me")
    @Authenticated
    public Uni<Response> me() {

        String externalId = identity.getPrincipal().getName();
        String email      = jwt.getClaim("email");
        String name       = jwt.getClaim("name");

        if (externalId == null || externalId.isBlank())
            return Uni.createFrom().item(
                    Response.status(Response.Status.UNAUTHORIZED).build());

        return onboardingService.provisionBasicUser(externalId, email, name)
                .map(tenantId -> {
                    // tenantId is null if user hasn't completed onboarding yet
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("externalId", externalId);
                    body.put("email",      email != null ? email : "");
                    body.put("name",       name  != null ? name  : "");
                    body.put("tenantId",   tenantId != null ? tenantId.toString() : null);
                    body.put("onboarded",  tenantId != null);
                    return Response.ok(body).build();
                })
                .onFailure().invoke(e ->
                        Log.errorf("provisionBasicUser failed for %s: %s",
                                externalId, e.getMessage()))
                .onFailure().recoverWithItem(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

    // -------------------------------------------------------------------------
    // POST /api/onboard/setup-tenant
    // Called ONCE from the Onboarding UI after the user picks account type.
    // Body: { "accountType": "INDIVIDUAL" | "ORGANIZATION",
    //         "companyName": "Acme Inc",   // required for ORGANIZATION
    //         "companySize": "11-50"        // optional
    //       }
    // -------------------------------------------------------------------------
    @POST
    @Path("/setup-tenant")
    @Authenticated
    public Uni<Response> setupTenant(SetupTenantRequest req) {

        String externalId = identity.getPrincipal().getName();
        String email      = jwt.getClaim("email");
        String name       = jwt.getClaim("name");

        if (externalId == null || externalId.isBlank())
            return Uni.createFrom().item(
                    Response.status(Response.Status.UNAUTHORIZED).build());

        // ── Validate ──────────────────────────────────────────────────────────
        if (req == null || req.accountType == null ||
                (!req.accountType.equals("INDIVIDUAL") && !req.accountType.equals("ORGANIZATION"))) {
            return Uni.createFrom().item(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("message", "accountType must be INDIVIDUAL or ORGANIZATION"))
                            .build());
        }

        if ("ORGANIZATION".equals(req.accountType) &&
                (req.companyName == null || req.companyName.isBlank())) {
            return Uni.createFrom().item(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("message", "companyName is required for ORGANIZATION"))
                            .build());
        }

        return onboardingService.setupTenant(externalId, email, name, req)
                .map(tenantId -> Response.status(Response.Status.CREATED)
                        .entity(Map.of(
                                "message",   "Tenant setup complete",
                                "tenantId",  tenantId.toString(),
                                "accountType", req.accountType
                        )).build())
                .onFailure().invoke(e ->
                        Log.errorf("setupTenant failed for %s: %s",
                                externalId, e.getMessage()))
                .onFailure().recoverWithItem(e -> {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("already")) {
                        return Response.status(Response.Status.CONFLICT)
                                .entity(Map.of("message", msg)).build();
                    }
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(Map.of("message", "Failed to set up tenant")).build();
                });
    }

    // Add to OnboardingResource.java

    @POST
    @Path("/repair-attributes")
    @Authenticated
    public Uni<Response> repairAttributes() {

        String keycloakSub = jwt.getSubject();

        return onboardingService.repairKeycloakAttributes(keycloakSub)
                .map(ok -> Response.ok(Map.of(
                        "message", "Attributes repaired",
                        "sub", keycloakSub
                )).build())
                .onFailure().recoverWithItem(e ->
                        Response.status(500)
                                .entity(Map.of("message", e.getMessage()))
                                .build());
    }

    // ── Request DTO ───────────────────────────────────────────────────────────
    public static class SetupTenantRequest {
        public String accountType;   // "INDIVIDUAL" | "ORGANIZATION"
        public String companyName;   // required for ORGANIZATION
        public String companySize;   // optional
    }
}