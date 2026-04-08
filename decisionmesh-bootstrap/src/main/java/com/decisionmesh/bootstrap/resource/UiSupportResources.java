package com.decisionmesh.bootstrap.resource;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UiSupportResources {

    // ── GET /api/credits/balance ──────────────────────────────────────────────

    @Path("/api/credits")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public static class CreditsResource {

        @GET
        @Path("/balance")
        public Map<String, Object> balance() {
            return Map.of(
                    "balance",           500,
                    "monthlyAllocation", 500,
                    "plan",              "free"
            );
        }
    }

    // ── GET /api/org ──────────────────────────────────────────────────────────

    @Path("/api/org")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public static class OrgResource {

        @Inject JsonWebToken jwt;

        @GET
        public Map<String, Object> getOrg() {
            String tid   = jwt.getClaim("tenantId");
            String email = jwt.getClaim("email");

            String name = (email != null && email.contains("@"))
                    ? email.split("@")[0] : "My Organisation";
            String orgName = Character.toUpperCase(name.charAt(0)) + name.substring(1);

            return Map.of(
                    "id",          tid != null ? tid : "",
                    "name",        orgName,
                    "plan",        "free",
                    "logoInitial", orgName.substring(0, 1).toUpperCase()
            );
        }
    }

    // ── GET /api/projects ─────────────────────────────────────────────────────

    @Path("/api/projects")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public static class ProjectsResource {

        @Inject JsonWebToken jwt;

        @GET
        public List<Map<String, Object>> list() {
            String tid = jwt.getClaim("tenantId");
            return List.of(Map.of(
                    "id",          tid != null ? tid : "",
                    "name",        "Default Project",
                    "environment", "Production",
                    "description", "Default project",
                    "isDefault",   true
            ));
        }

        @POST
        public Map<String, Object> create(Map<String, Object> body) {
            return Map.of(
                    "id",          UUID.randomUUID().toString(),
                    "name",        body.getOrDefault("name", "New Project"),
                    "environment", body.getOrDefault("environment", "Production"),
                    "description", body.getOrDefault("description", ""),
                    "isDefault",   false
            );
        }
    }
}
