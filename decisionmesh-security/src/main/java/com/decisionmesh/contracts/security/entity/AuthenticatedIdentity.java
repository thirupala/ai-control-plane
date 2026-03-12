package com.decisionmesh.contracts.security.entity;

import io.quarkus.security.credential.Credential;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedIdentity(
        UUID userId,
        UUID tenantId,
        String email,
        String username,
        Set<String> roles
) implements Credential {

}