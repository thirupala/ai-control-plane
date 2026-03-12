package com.decisionmesh.contracts.security.normalizer;

import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

public interface OidcClaimsNormalizer {
    AuthenticatedIdentity normalize(JsonWebToken jwt);
    boolean supports(String issuer); // picks the right impl
}
