package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.OrganizationEntity;
import com.decisionmesh.contracts.security.entity.UserEntity;
import com.decisionmesh.contracts.security.resource.dto.SignupRequest;
import com.decisionmesh.contracts.security.resource.dto.SignupResponse;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class SignupService {

    private static final Logger LOG = Logger.getLogger(SignupService.class);

    @Inject
    SecurityIdentity identity;

    @Inject
    UserService userService;

    @Inject
    TenantService tenantService;

    @Inject
    ApiKeyService apiKeyService;

    @Inject
    UserOrganizationService userOrganizationService;

    @Inject
    KeycloakProvisioningService keycloakProvisioningService;

    @Transactional
    public SignupResponse onboard(SignupRequest request) {

        String externalUserId = identity.getPrincipal().getName();
        String email          = identity.getAttribute("email");
        String name           = identity.getAttribute("name");  //  from token

        //  Already onboarded — return existing
        UserEntity existing = userService.findByExternalUserId(externalUserId);
        if (existing != null) {
            return buildExistingResponse(existing);
        }

        //  Create tenant
        UUID tenantId = tenantService.createTenant(
                request.organizationName,
                request.idempotencyKey
        );

        //  Fix 1 — createExternalUser takes (externalId, email, name) all Strings
        UserEntity user = userService.createExternalUser(
                externalUserId,
                email,
                name            //  String not UUID
        );

        //  Create default org under tenant
        OrganizationEntity org = tenantService.createDefaultOrganization(
                tenantId,
                request.organizationName
        );

        //  Fix 2 — use org.organizationId (match your OrganizationEntity field name)
        userOrganizationService.createMembership(
                user.userId,
                org.id,
                tenantId,
                "OWNER"
        );

        //  Create API key
        ApiKeyService.ApiKeyResult apiKey = apiKeyService.createApiKey(
                org.id,
                tenantId,
                user.userId,
                "Default API Key",
                false,
                30
        );

        //  Assign Keycloak role
        keycloakProvisioningService.assignTenantAdminRole(
                externalUserId,
                tenantId
        );

        LOG.infof("Onboarded: user=%s tenant=%s", email, tenantId);

        return new SignupResponse(
                user.userId.toString(),
                tenantId,           //  plain UUID not Optional
                apiKey.key
        );
    }

    private SignupResponse buildExistingResponse(UserEntity user) {
        //  Fix 3 — unwrap Optional<UUID> with orElseThrow
        UUID tenantId = userOrganizationService
                .findTenantIdByUserId(user.userId)
                .orElseThrow(() -> new IllegalStateException(
                        "User exists but has no tenant: " + user.userId));

        return new SignupResponse(
                user.userId.toString(),
                tenantId,           //  plain UUID
                null
        );
    }
}