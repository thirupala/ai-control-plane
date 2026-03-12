package com.decisionmesh.contracts.security.service;


import com.decisionmesh.contracts.security.entity.Organization;
import com.decisionmesh.contracts.security.entity.User;
import com.decisionmesh.contracts.security.resource.dto.SignupRequest;
import com.decisionmesh.contracts.security.resource.dto.SignupResponse;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.UUID;

@ApplicationScoped
public class SignupService {

    @Inject
    SecurityIdentity identity;

    @Inject
    UserService userService;

    @Inject
    TenantService tenantService;

    @Inject
    ApiKeyService apiKeyService;
    @Inject
    KeycloakProvisioningService keycloakProvisioningService;

    public SignupResponse onboard(SignupRequest request) {

        String externalUserId = identity.getPrincipal().getName();
        String email = identity.getAttribute("email");

        User existing = userService.findByExternalUserId(externalUserId);

        if (existing != null) {
            return buildExistingResponse(existing);
        }

        UUID tenantId = tenantService.createTenant(request.organizationName,request.idempotencyKey);

        User user = userService.createExternalUser(
                externalUserId,
                email,
                tenantId
        );

        Organization org = tenantService.createDefaultOrganization(
                tenantId,
                request.organizationName
        );

        ApiKeyService.ApiKeyResult apiKey =
                apiKeyService.createApiKey(
                        org.organizationId,
                        tenantId,
                        user.userId,
                        "Default API Key",
                        false,
                        30
                );

        return new SignupResponse(
                user.userId.toString(),
                tenantId,
                apiKey.key
        );
    }

    @Transactional
    public void autoOnboard(SecurityIdentity identity) {

        String externalUserId = identity.getPrincipal().getName();
        String email = identity.getAttribute("email");

        // Prevent race condition
        if (userService.findByExternalUserId(externalUserId) != null) {
            return;
        }

        UUID tenantId = tenantService.createTenant(
                "Default Organization",
                externalUserId  // using sub as idempotency anchor
        );

        User user = userService.createExternalUser(
                externalUserId,
                email,
                tenantId
        );

        var org = tenantService.createDefaultOrganization(
                tenantId,
                "Default Organization"
        );

        apiKeyService.createApiKey(
                org.organizationId,
                tenantId,
                user.userId,
                "Default API Key",
                false,
                30
        );

        keycloakProvisioningService.assignTenantAdminRole(
                externalUserId,
                tenantId
        );
    }

    private SignupResponse buildExistingResponse(User user) {
        return new SignupResponse(
                user.userId.toString(),
                user.tenantId,
                null // or fetch existing key if needed
        );
    }
}