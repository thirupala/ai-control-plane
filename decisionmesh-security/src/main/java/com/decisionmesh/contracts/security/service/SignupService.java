package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.UserEntity;
import com.decisionmesh.contracts.security.resource.dto.SignupRequest;
import com.decisionmesh.contracts.security.resource.dto.SignupResponse;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class SignupService {

    private static final Logger LOG = Logger.getLogger(SignupService.class);

    @Inject SecurityIdentity            identity;
    @Inject UserService                 userService;
    @Inject TenantService               tenantService;
    @Inject ApiKeyService               apiKeyService;
    @Inject UserOrganizationService     userOrganizationService;
    @Inject KeycloakProvisioningService keycloakProvisioningService;

    // ── Static holder classes ─────────────────────────────────────────────────

    private static final class TenantUser {
        final UUID       tenantId;
        final UserEntity user;
        TenantUser(UUID tenantId, UserEntity user) {
            this.tenantId = tenantId;
            this.user     = user;
        }
    }

    private static final class TenantUserOrg {
        final UUID       tenantId;
        final UserEntity user;
        final UUID       orgId;
        TenantUserOrg(UUID tenantId, UserEntity user, UUID orgId) {
            this.tenantId = tenantId;
            this.user     = user;
            this.orgId    = orgId;
        }
    }

    private static final class TenantUserOrgKey {
        final UUID                       tenantId;
        final UserEntity                 user;
        final ApiKeyService.ApiKeyResult apiKey;
        TenantUserOrgKey(UUID tenantId, UserEntity user, ApiKeyService.ApiKeyResult apiKey) {
            this.tenantId = tenantId;
            this.user     = user;
            this.apiKey   = apiKey;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Uni<SignupResponse> onboard(SignupRequest request) {
        JsonWebToken jwt = (JsonWebToken) identity.getPrincipal();
        String externalUserId = jwt.getSubject();
        String email          = identity.getAttribute("email");
        String name           = identity.getAttribute("name");

        // UserService.findByExternalUserId() now returns Uni<UserEntity> directly
        return userService.findByExternalUserId(externalUserId)
                .flatMap((UserEntity existing) -> {
                    if (existing != null) {
                        return buildExistingResponse(existing);
                    }
                    return onboardNew(request, externalUserId, email, name);
                });
    }

    // ── New onboarding pipeline ───────────────────────────────────────────────

    private Uni<SignupResponse> onboardNew(SignupRequest request,
                                           String externalUserId,
                                           String email,
                                           String name) {

        // Step 2 — create tenant (idempotent)
        return tenantService.createTenant(request.organizationName, request.idempotencyKey)

                // Step 3 — create user
                // UserService.createExternalUser() now returns Uni<UserEntity> — use flatMap
                .flatMap((UUID tenantId) ->
                        userService.createExternalUser(externalUserId, email, name)
                                .map((UserEntity user) -> new TenantUser(tenantId, user))
                )

                // Step 4 — create default organization
                .flatMap((TenantUser ctx) ->
                        tenantService
                                .createDefaultOrganization(ctx.tenantId, request.organizationName)
                                .map(org -> new TenantUserOrg(ctx.tenantId, ctx.user, org.id))
                )

                // Step 5 — create membership
                .flatMap((TenantUserOrg ctx) ->
                        userOrganizationService
                                .createMembership(
                                        ctx.user.userId,
                                        ctx.orgId,
                                        ctx.tenantId,
                                        "OWNER")
                                .map(ignored -> ctx)
                )

                // Step 6 — create default API key
                .flatMap((TenantUserOrg ctx) ->
                        apiKeyService
                                .createApiKey(
                                        ctx.orgId,
                                        ctx.tenantId,
                                        ctx.user.userId,
                                        "Default API Key",
                                        false,
                                        30)
                                .map((ApiKeyService.ApiKeyResult apiKey) ->
                                        new TenantUserOrgKey(ctx.tenantId, ctx.user, apiKey))
                )

                // Step 7 — assign Keycloak role (non-fatal on failure)
                // assignTenantAdminRole() returns void — wrap it so failures
                // are recoverable without crashing the pipeline.
                .flatMap((TenantUserOrgKey ctx) -> {
                    Uni<Void> keycloak;
                    try {
                        keycloakProvisioningService.assignTenantAdminRole(
                                externalUserId, ctx.tenantId);
                        keycloak = Uni.createFrom().voidItem();
                    } catch (Exception ex) {
                        LOG.warnf("Keycloak role assignment failed (non-fatal): " +
                                "user=%s tenant=%s", externalUserId, ctx.tenantId);
                        keycloak = Uni.createFrom().voidItem();
                    }
                    return keycloak.map(ignored -> {
                        LOG.infof("Onboarded: user=%s tenant=%s", email, ctx.tenantId);
                        return new SignupResponse(
                                ctx.user.userId.toString(),
                                ctx.tenantId,
                                ctx.apiKey.key);
                    });
                });
    }

    // ── Existing user path ────────────────────────────────────────────────────

    private Uni<SignupResponse> buildExistingResponse(UserEntity user) {
        return userOrganizationService
                .findTenantIdByUserId(user.userId)
                .map((UUID tenantId) -> {
                    if (tenantId == null) {
                        throw new IllegalStateException(
                                "User exists but has no tenant: " + user.userId);
                    }
                    return new SignupResponse(user.userId.toString(), tenantId, null);
                });
    }
}