package com.decisionmesh.contracts.security.filter;

import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.contracts.security.normalizer.OidcClaimsNormalizer;
import io.quarkus.oidc.runtime.OidcJwtCallerPrincipal;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class IdentityAugmentor implements SecurityIdentityAugmentor {

    @Inject
    Instance<OidcClaimsNormalizer> normalizers; // picks up ALL implementations

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext ctx) {
        if (!(identity.getPrincipal() instanceof OidcJwtCallerPrincipal principal)) {
            return Uni.createFrom().item(identity);
        }

        JsonWebToken jwt = principal;
        String issuer = jwt.getIssuer();

        OidcClaimsNormalizer normalizer = normalizers.stream()
                .filter(n -> n.supports(issuer))
                .findFirst()
                .orElseThrow(() -> new AuthenticationFailedException(
                        "No normalizer found for issuer: " + issuer
                ));

        AuthenticatedIdentity authIdentity = normalizer.normalize(jwt);

        // Attach as a credential so downstream code can inject it
        return Uni.createFrom().item(
                QuarkusSecurityIdentity.builder(identity)
                        .addCredential(authIdentity)
                        .addRoles(authIdentity.roles())
                        .build()
        );
    }
}
