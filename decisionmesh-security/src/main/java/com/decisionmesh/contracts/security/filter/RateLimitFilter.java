package com.decisionmesh.contracts.security.filter;

import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.contracts.security.ratelimit.RateLimitPolicy;
import com.decisionmesh.contracts.security.ratelimit.RedisRateLimiter;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.UUID;

/**
 * Rate limiting filter using RESTEasy Reactive's @ServerRequestFilter.
 * Returns Uni<Response>: null response = continue chain, non-null = abort with that response.
 * Uses Vertx.executeBlocking() to run blocking Redis calls off the event loop.
 */
@ApplicationScoped
public class RateLimitFilter {

    @Inject
    TenantContext tenantContext;

    @Inject
    RedisRateLimiter rateLimiter;

    @Inject
    RateLimitPolicy policy;

    @Inject
    Vertx vertx;

    @ServerRequestFilter(priority = 5020)
    public Uni<Response> filter(ContainerRequestContext requestContext) {

        // Skip CORS preflight immediately on event loop
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            return Uni.createFrom().nullItem();
        }

        // Capture values before leaving event loop thread
        String method = requestContext.getMethod();
        String path   = requestContext.getUriInfo().getPath();

        // Offload all blocking Redis calls to a worker thread
        return vertx.executeBlocking(
                Uni.createFrom().item(() -> doRateLimit(method, path))
        );
    }

    /**
     * Runs on a worker thread via executeBlocking.
     * Returns null to continue, or a Response to abort the request.
     */
    private Response doRateLimit(String method, String path) {

        UUID tenantId;

        try {
            tenantId = tenantContext.tenantId();
        } catch (WebApplicationException e) {
            Log.debugf("No tenant context for rate limiting on path: %s — skipping", path);
            return null;
        }

        int windowSeconds = policy.windowSeconds();

        // -------------------------
        // API key rate limiting
        // -------------------------
        if (tenantContext.isApiKey()) {
            String apiKeyKey = "ratelimit:apikey:" + tenantContext.userId();
            if (!rateLimiter.allow(apiKeyKey, policy.apiKeyLimit(), windowSeconds)) {
                Log.warnf("API key rate limit exceeded for user: %s", tenantContext.userId());
                return rateLimitResponse(windowSeconds);
            }
        }

        // -------------------------
        // Tenant-wide rate limiting
        // -------------------------
        String tenantKey = "ratelimit:tenant:" + tenantId;
        if (!rateLimiter.allow(tenantKey, policy.tenantLimit(tenantId), windowSeconds)) {
            Log.warnf("Tenant rate limit exceeded for tenant: %s", tenantId);
            return rateLimitResponse(windowSeconds);
        }

        // -------------------------
        // Intent submission limits
        // -------------------------
        if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/intents")) {
            String intentKey = "ratelimit:tenant:" + tenantId + ":intent";
            if (!rateLimiter.allow(intentKey, policy.intentSubmitLimit(), windowSeconds)) {
                Log.warnf("Intent submission rate limit exceeded for tenant: %s", tenantId);
                return rateLimitResponse(windowSeconds);
            }
        }

        return null; // null = continue to next filter/resource
    }

    private Response rateLimitResponse(int windowSeconds) {
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .header("Retry-After", windowSeconds)
                .entity("Too Many Requests")
                .build();
    }
}
