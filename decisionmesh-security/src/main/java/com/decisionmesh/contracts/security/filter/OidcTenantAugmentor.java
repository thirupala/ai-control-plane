package com.decisionmesh.contracts.security.filter;

import io.quarkus.oidc.runtime.OidcJwtCallerPrincipal;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class OidcTenantAugmentor implements SecurityIdentityAugmentor {

    private static final Logger LOG = Logger.getLogger(OidcTenantAugmentor.class);

    // @Inject YourTenantLookupService tenantService; // inject when ready

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context) {

        // Only augment OIDC identities (not API key ones)
        if (identity.isAnonymous()
                || !(identity.getPrincipal() instanceof OidcJwtCallerPrincipal)) {
            return Uni.createFrom().item(identity);
        }

        OidcJwtCallerPrincipal principal = (OidcJwtCallerPrincipal) identity.getPrincipal();
        String sub = principal.getClaim("sub"); // Keycloak user UUID

        // TODO: swap this for a real DB lookup:
        // UUID tenantId = tenantService.findTenantByUserId(UUID.fromString(sub));
        UUID userId = UUID.fromString(sub);
        UUID tenantId = userId; // ← TEMPORARY: use sub as tenantId until tenant table exists

        LOG.debugf("OIDC augment: sub=%s tenantId=%s", sub, tenantId);

        return Uni.createFrom().item(
                QuarkusSecurityIdentity.builder(identity)
                        .addAttribute("tenantId", tenantId)
                        .addAttribute("userId", userId)
                        .addAttribute("authType", "oidc")
                        .build()
        );
    }
}