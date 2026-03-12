package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class UserService {

    @Transactional
    public User createExternalUser(
            String externalUserId,   // OIDC "sub"
            String email,
            UUID tenantId
    ) {
        // Ensure uniqueness by external user ID
        if (User.find("externalUserId", externalUserId).firstResult() != null) {
            throw new IllegalStateException("User already exists");
        }

        User user = new User();
        user.userId = UUID.randomUUID();   // internal DB id
        user.externalUserId = externalUserId;
        user.email = email;
        user.tenantId = tenantId;
        user.createdAt = Instant.now();
        user.active = true;

        user.persist();
        return user;
    }
    @Transactional
    public User findByExternalUserId(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) {
            return null;
        }

        return User.find("externalUserId", externalUserId)
                .firstResult();

    }
}
