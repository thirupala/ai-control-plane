package com.decisionmesh.contracts.security.normalizer;

import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class KeycloakClaimsNormalizer implements OidcClaimsNormalizer {

    @Override
    public boolean supports(String issuer) {
        return issuer.contains("/realms/");
    }

    @Override
    public AuthenticatedIdentity normalize(JsonWebToken jwt) {
        // getClaim returns a Map for JSON objects — no casting to any JsonObject
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");

        Set<String> roles = Set.of();
        if (realmAccess != null) {
            Object rolesObj = realmAccess.get("roles");
            if (rolesObj instanceof Collection<?> rolesList) {
                roles = rolesList.stream()
                        .map(Object::toString)
                        .collect(Collectors.toSet());
            }
        }

        return new AuthenticatedIdentity(
                UUID.fromString(jwt.getSubject()),
                // fallback to a nil UUID if tenant_id not yet in token
                jwt.<String>getClaim("tenant_id") != null
                        ? UUID.fromString(jwt.<String>getClaim("tenant_id"))
                        : UUID.fromString("00000000-0000-0000-0000-000000000000"),
                jwt.getClaim("email"),
                jwt.getClaim("preferred_username"),
                roles
        );
    }
}