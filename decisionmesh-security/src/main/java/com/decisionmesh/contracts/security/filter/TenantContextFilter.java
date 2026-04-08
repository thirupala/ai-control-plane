package com.decisionmesh.contracts.security.filter;

import com.decisionmesh.contracts.security.context.TenantContext;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.UUID;

@Provider
@Priority(Priorities.AUTHENTICATION + 10)
public class TenantContextFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(TenantContextFilter.class);

    @Inject SecurityIdentity securityIdentity;
    @Inject TenantContext    tenantContext;
    @Inject JsonWebToken     jwt;

    @Override
    public void filter(ContainerRequestContext requestContext) {

        String path = requestContext.getUriInfo().getPath();

        // Skip OPTIONS, metrics, health — no identity needed
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())
                || path.contains("metrics")
                || path.contains("health")) {
            return;
        }

        // Skip anonymous requests
        if (securityIdentity.isAnonymous()) {
            return;
        }

        UUID tenantId = null;
        UUID userId   = null;

        // ── Path 1: API key request ───────────────────────────────────────────
        // Attributes set by SecretKeyAuthenticationMechanism
        Object tidAttr = securityIdentity.getAttribute("tenantId");
        Object uidAttr = securityIdentity.getAttribute("userId");
        if (tidAttr instanceof UUID tid && uidAttr instanceof UUID uid) {
            tenantId = tid;
            userId   = uid;
        }

        // ── Path 2: JWT/OIDC request ──────────────────────────────────────────
        // OidcTenantAugmentor deleted — read claims directly from JWT
        if (tenantId == null) {
            String tidClaim = jwt.getClaim("tenantId");
            String uidClaim = jwt.getClaim("userId");
            if (tidClaim != null && !tidClaim.isBlank()) {
                try {
                    tenantId = UUID.fromString(tidClaim);
                    userId   = (uidClaim != null && !uidClaim.isBlank())
                            ? UUID.fromString(uidClaim)
                            : null;
                } catch (IllegalArgumentException e) {
                    LOG.warnf("Malformed tenantId/userId claim for user: %s path: %s",
                            securityIdentity.getPrincipal().getName(), path);
                }
            }
        }

        // ── No tenantId resolved — log and skip (do NOT throw) ────────────────
        // Throwing here causes redirect loops during OIDC sessions
        if (tenantId == null) {
            LOG.warnf("No tenantId resolved for user: %s path: %s",
                    securityIdentity.getPrincipal().getName(), path);
            return;
        }

        // ── Set TenantContext for RateLimitFilter ─────────────────────────────
        // Idempotent check prevents "TenantContext already set" collisions
        if (tenantContext.getTenantId() == null) {
            try {
                String role = securityIdentity.getAttribute("role");
                tenantContext.setUserContext(tenantId, userId, role);
                LOG.debugf("Context set: tenant=%s, user=%s, path=%s", tenantId, userId, path);
            } catch (IllegalStateException e) {
                LOG.warnf("TenantContext collision ignored for path: %s", path);
            }
        }
    }
}