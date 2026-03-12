package com.decisionmesh.contracts.security.filter;

import com.decisionmesh.contracts.security.context.TenantContext;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.UUID;

@Provider
@Priority(Priorities.AUTHENTICATION + 10) // Run after authentication
public class TenantContextFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(TenantContextFilter.class);

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    TenantContext tenantContext;

    @Override
    public void filter(ContainerRequestContext requestContext) {

        // Skip CORS preflight
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }

        // Skip anonymous requests
        if (securityIdentity.isAnonymous()) {
            LOG.debug("Anonymous request - tenant context not set");
            return;
        }

        UUID tenantId = securityIdentity.getAttribute("tenantId");
        UUID userId = securityIdentity.getAttribute("userId");
        String apiKeyId = securityIdentity.getAttribute("apiKeyId");
        String authType = securityIdentity.getAttribute("authType");

        if (tenantId == null) {
            LOG.warn("Missing tenantId attribute in SecurityIdentity");
            throw new NotAuthorizedException("Missing tenant identity");
        }

        tenantContext.set(tenantId, apiKeyId, userId, authType);

        LOG.debugf(
                "TenantContext set: tenant=%s apiKey=%s user=%s path=%s",
                tenantId,
                apiKeyId,
                userId,
                requestContext.getUriInfo().getPath()
        );
    }
}