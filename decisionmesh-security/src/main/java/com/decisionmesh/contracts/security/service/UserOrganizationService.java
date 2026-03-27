package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.UserOrganizationEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UserOrganizationService {

    private static final Logger LOG = Logger.getLogger(UserOrganizationService.class);

    @Transactional
    public UserOrganizationEntity createMembership(
            UUID userId,
            UUID orgId,
            UUID tenantId,
            String role) {

        return createMembership(userId, orgId, tenantId, role, List.of());
    }

    @Transactional
    public UserOrganizationEntity createMembership(
            UUID userId,
            UUID orgId,
            UUID tenantId,
            String role,
            List<String> permissions) {           //  JSONB permissions as List

        //  Guard against duplicate membership
        boolean exists = UserOrganizationEntity
                .count("userId = ?1 and organizationId = ?2", userId, orgId) > 0;

        if (exists) {
            LOG.warnf("Membership already exists: userId=%s orgId=%s", userId, orgId);
            return UserOrganizationEntity
                    .find("userId = ?1 and organizationId = ?2", userId, orgId)
                    .<UserOrganizationEntity>firstResult();
        }

        UserOrganizationEntity membership = new UserOrganizationEntity();
        membership.userId         = userId;
        membership.organizationId = orgId;
        membership.tenantId       = tenantId;
        membership.role           = role;
        membership.permissions    = "[]";
        membership.isActive       = true;
        membership.createdAt      = Instant.now();
        membership.updatedAt      = Instant.now();
        membership.persist();

        LOG.infof("Created membership: userId=%s orgId=%s tenantId=%s role=%s",
                userId, orgId, tenantId, role);

        return membership;
    }

    //  Safe — returns Optional to avoid NPE
    public Optional<UUID> findTenantIdByUserId(UUID userId) {
        return UserOrganizationEntity
                .find("userId = ?1 and isActive = true", userId)
                .<UserOrganizationEntity>firstResultOptional()
                .map(m -> m.tenantId);
    }

    //  Find all active memberships for a user
    public List<UserOrganizationEntity> findAllByUserId(UUID userId) {
        return UserOrganizationEntity
                .find("userId = ?1 and isActive = true", userId)
                .list();
    }

    //  Find membership for specific tenant
    public Optional<UserOrganizationEntity> findByUserAndTenant(UUID userId, UUID tenantId) {
        return UserOrganizationEntity
                .find("userId = ?1 and tenantId = ?2 and isActive = true",
                        userId, tenantId)
                .<UserOrganizationEntity>firstResultOptional();
    }

    //  Deactivate membership (soft delete)
    @Transactional
    public void deactivateMembership(UUID userId, UUID orgId) {
        UserOrganizationEntity membership = UserOrganizationEntity
                .find("userId = ?1 and organizationId = ?2", userId, orgId)
                .<UserOrganizationEntity>firstResult();

        if (membership != null) {
            membership.isActive  = false;
            membership.updatedAt = Instant.now();
        }
    }
}