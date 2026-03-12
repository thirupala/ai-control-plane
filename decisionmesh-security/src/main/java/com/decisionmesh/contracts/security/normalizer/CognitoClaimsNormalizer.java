package com.decisionmesh.contracts.security.normalizer;

import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class CognitoClaimsNormalizer implements OidcClaimsNormalizer {

    @Override
    public boolean supports(String issuer) {
        return issuer.contains("cognito-idp"); // Cognito issuer pattern
    }

    @Override
    public AuthenticatedIdentity normalize(JsonWebToken jwt) {
        String groupsClaim = jwt.getClaim("cognito:groups");
        Set<String> roles = groupsClaim != null
                ? Set.of(groupsClaim.split(","))
                : Set.of();

        return new AuthenticatedIdentity(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.<String>getClaim("custom:tenant_id")),
                jwt.getClaim("email"),
                jwt.getClaim("cognito:username"),
                roles
        );
    }
}
