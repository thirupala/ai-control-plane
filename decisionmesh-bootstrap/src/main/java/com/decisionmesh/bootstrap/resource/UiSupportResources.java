package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight stubs for UI context providers.
 *
 * CreditContext.jsx  → GET /api/credits/balance
 * ProjectContext.jsx → GET /api/org  +  GET /api/projects
 *
 * All three contexts fall back to hard-coded defaults when the API
 * returns an error, so these endpoints just need to return valid JSON
 * with the right field names. No DB queries — derive from identity.
 */
public class UiSupportResources {

    // ── GET /api/credits/balance ──────────────────────────────────────────────

    @Path("/api/credits")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public static class CreditsResource {

        @Inject SecurityIdentity securityIdentity;

        /**
         * Returns a static credit balance.
         *
         * To make this dynamic: query credit_ledger SUM(amount) WHERE org_id IN
         * (SELECT id FROM organizations WHERE tenant_id = ?).
         * For now returns 500 credits so the UI shows a healthy balance bar.
         *
         * Shape expected by CreditContext.jsx:
         *   { balance: number, monthlyAllocation: number, plan: string }
         */
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

        @Inject SecurityIdentity securityIdentity;

        /**
         * Returns org info derived from the authenticated identity.
         *
         * Shape expected by ProjectContext.jsx:
         *   { id, name, plan, logoInitial }
         *
         * To make dynamic: query organizations WHERE tenant_id = auth.tenantId()
         * and return the first result.
         */
        @GET
        public Map<String, Object> getOrg() {
            AuthenticatedIdentity auth = resolveIdentity();
            // Use email prefix as org name until orgs are fully wired
            String name = auth.email() != null
                    ? auth.email().split("@")[0]
                    : "My Organisation";
            // Capitalise first letter
            String orgName = name.length() > 0
                    ? Character.toUpperCase(name.charAt(0)) + name.substring(1)
                    : "My Organisation";
            return Map.of(
                    "id",          auth.tenantId().toString(),
                    "name",        orgName,
                    "plan",        "free",
                    "logoInitial", orgName.substring(0, 1).toUpperCase()
            );
        }

        private AuthenticatedIdentity resolveIdentity() {
            AuthenticatedIdentity auth =
                    securityIdentity.getCredential(AuthenticatedIdentity.class);
            if (auth == null) throw new NotAuthorizedException("Identity not resolved");
            return auth;
        }
    }

    // ── GET /api/projects ─────────────────────────────────────────────────────

    @Path("/api/projects")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "tenant_admin", "tenant_user"})
    public static class ProjectsResource {

        @Inject SecurityIdentity securityIdentity;

        /**
         * Returns a single default project derived from the tenant.
         *
         * Shape expected by ProjectContext.jsx (each item):
         *   { id, name, environment, description, isDefault }
         *
         * To make dynamic: query the projects table WHERE tenant_id = auth.tenantId().
         */
        @GET
        public List<Map<String, Object>> list() {
            AuthenticatedIdentity auth = resolveIdentity();
            return List.of(Map.of(
                    "id",          auth.tenantId().toString(),
                    "name",        "Default Project",
                    "environment", "Production",
                    "description", "Default project",
                    "isDefault",   true
            ));
        }

        /**
         * Accepts a project creation request and echoes it back.
         * Projects.jsx posts: { name, environment, description }.
         */
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

        private AuthenticatedIdentity resolveIdentity() {
            AuthenticatedIdentity auth =
                    securityIdentity.getCredential(AuthenticatedIdentity.class);
            if (auth == null) throw new NotAuthorizedException("Identity not resolved");
            return auth;
        }
    }
}