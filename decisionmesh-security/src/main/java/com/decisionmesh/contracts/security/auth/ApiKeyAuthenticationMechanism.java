package com.decisionmesh.contracts.security.auth;

import com.decisionmesh.contracts.security.entity.ApiKey;
import com.decisionmesh.contracts.security.service.ApiKeyService;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.Principal;
import java.util.HashSet;
import java.util.Set;

/**
 * Custom HTTP authentication mechanism for API keys.
 * Does NOT use TenantContext during authentication to avoid RequestScoped issues.
 * TenantContext is set by TenantContextFilter AFTER authentication.
 */
@ApplicationScoped
public class ApiKeyAuthenticationMechanism implements HttpAuthenticationMechanism {

    private static final Logger LOG = Logger.getLogger(ApiKeyAuthenticationMechanism.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String API_KEY_PREFIX_LIVE = "sk_live_";
    private static final String API_KEY_PREFIX_TEST = "sk_test_";

    @Inject
    ApiKeyService apiKeyService;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {

        // Short-circuit OPTIONS before anything else
        if ("OPTIONS".equals(context.request().method().name())) {
            return Uni.createFrom().nullItem();
        }

        String path = context.normalizedPath();

        LOG.debugf(" API Key Auth: %s %s", context.request().method(), path);

        // Skip authentication for public endpoints
        if (isPublicEndpoint(path)) {
            LOG.debugf(" Public endpoint - skipping: %s", path);
            return Uni.createFrom().nullItem();
        }

        // Get Authorization header
        String authHeader = context.request().getHeader(AUTHORIZATION_HEADER);

        if (authHeader == null || authHeader.isBlank()) {
            LOG.debugf("No Authorization header - skipping API key auth");
            return Uni.createFrom().nullItem();
        }

        // Extract credential
        String credential = extractCredential(authHeader);

        // Check if it's an API key
        if (!isApiKey(credential)) {
            LOG.debugf("Not an API key - skipping");
            return Uni.createFrom().nullItem();
        }

        // Validate API key - wrap blocking call in Uni
        LOG.debugf(" Validating API key: %s...", credential.substring(0, Math.min(15, credential.length())));

        //  Execute blocking database call on Vert.x blocking executor, convert Future to Uni
        return Uni.createFrom().completionStage(() ->
                context.vertx().executeBlocking(() -> {
                    ApiKey apiKey = apiKeyService.validateAndGetKey(credential);

                    if (apiKey == null) {
                        LOG.warnf("❌ Invalid API key");
                        return null;
                    }

                    // Extract roles from scopes
                    Set<String> roles = extractRolesFromScopes(apiKey.scopes);

                    LOG.infof(" API Key authenticated: tenant=%s, key=%s, user=%s, roles=%s",
                            apiKey.tenantId, apiKey.keyPrefix, apiKey.createdByUserId, roles);

                    // Build security identity with roles and attributes
                    // Note: We store tenant info in attributes, not in TenantContext yet
                    QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder();
                    builder.setPrincipal(new ApiKeyPrincipal(apiKey));
                    builder.addRoles(roles);

                    // Store all tenant info as attributes
                    builder.addAttribute("api.key.entity", apiKey);
                    builder.addAttribute("tenantId", apiKey.tenantId);
                    builder.addAttribute("userId", apiKey.createdByUserId);
                    builder.addAttribute("apiKeyId", apiKey.keyId.toString());

                    SecurityIdentity identity = builder.build();

                    LOG.debugf(" Built SecurityIdentity - Principal: %s, Roles: %s, Anonymous: %s",
                            identity.getPrincipal().getName(), identity.getRoles(), identity.isAnonymous());

                    return identity;
                }).toCompletionStage()
        );
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        ChallengeData challenge = new ChallengeData(
                401,
                "WWW-Authenticate",
                "Bearer"
        );
        return Uni.createFrom().item(challenge);
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of();
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().item(new HttpCredentialTransport(
                HttpCredentialTransport.Type.AUTHORIZATION,
                "bearer"
        ));
    }

    /**
     * Check if credential is an API key.
     */
    private boolean isApiKey(String credential) {
        return credential.startsWith(API_KEY_PREFIX_LIVE)
                || credential.startsWith(API_KEY_PREFIX_TEST);
    }

    /**
     * Extract credential from Authorization header.
     */
    private String extractCredential(String authHeader) {
        if (authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        return authHeader.trim();
    }

    /**
     * Check if endpoint is public.
     */
    private boolean isPublicEndpoint(String path) {
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;

        boolean isPublic = normalizedPath.equals("signup")
                || normalizedPath.startsWith("signup/")
                || normalizedPath.equals("health")
                || normalizedPath.startsWith("health/")
                || normalizedPath.startsWith("q/")
                || normalizedPath.startsWith("metrics")
                || normalizedPath.startsWith("swagger-ui")
                || normalizedPath.startsWith("openapi");

        if (isPublic) {
            LOG.debugf("Public endpoint detected: %s", path);
        } else {
            LOG.debugf("Protected endpoint: %s", path);
        }

        return isPublic;
    }

    /**
     * Extract roles from API key scopes.
     */
    private Set<String> extractRolesFromScopes(String scopesJson) {
        Set<String> roles = new HashSet<>();

        LOG.debugf(" Extracting roles from scopes: %s", scopesJson);

        if (scopesJson == null || scopesJson.isBlank()) {
            // No scopes = full access
            LOG.debugf("No scopes defined - granting full access");
            roles.add("decision:submit");
            roles.add("decision:read");
            roles.add("intent:submit");
            LOG.debugf(" Granted roles: %s", roles);
            return roles;
        }

        // Simple JSON parsing - handle ["*"], ["scope1","scope2"], etc.
        String scopes = scopesJson.replace("[", "").replace("]", "").replace("\"", "").replace("'", "");

        LOG.debugf("Parsed scopes string: %s", scopes);

        for (String scope : scopes.split(",")) {
            scope = scope.trim();

            if (scope.equals("*")) {
                // Wildcard = all permissions
                LOG.debugf("Wildcard scope detected - granting all permissions");
                roles.add("decision:submit");
                roles.add("decision:read");
                roles.add("intent:submit");
                roles.add("apikey:manage");
            } else if (!scope.isEmpty()) {
                LOG.debugf("Adding role: %s", scope);
                roles.add(scope);
            }
        }

        LOG.debugf(" Final roles: %s", roles);
        return roles;
    }

    /**
         * Custom principal for API key authentication.
         */
        public record ApiKeyPrincipal(ApiKey apiKey) implements Principal {

        @Override
            public String getName() {
                return apiKey.createdByUserId.toString();
            }
        }
}