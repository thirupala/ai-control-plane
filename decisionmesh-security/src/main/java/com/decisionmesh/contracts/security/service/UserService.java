package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.UserEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class UserService {

    @Transactional
    public UserEntity createExternalUser(
            String externalUserId,
            String email,
            String name) {

        UserEntity user     = new UserEntity();
        user.externalUserId = externalUserId;
        user.email          = email;
        user.name           = name;
        user.isActive       = true;
        user.createdAt      = Instant.now();
        user.updatedAt      = Instant.now();
        user.persist();
        return user;
    }
    @Transactional
    public UserEntity findByExternalUserId(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) {
            return null;
        }

        return UserEntity.find("externalUserId", externalUserId)
                .firstResult();

    }
}
