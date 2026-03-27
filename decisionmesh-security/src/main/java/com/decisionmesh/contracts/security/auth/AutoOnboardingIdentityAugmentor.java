package com.decisionmesh.contracts.security.auth;

import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.contracts.security.entity.UserEntity;
import com.decisionmesh.contracts.security.service.TenantService;
import com.decisionmesh.contracts.security.service.UserOrganizationService;
import com.decisionmesh.contracts.security.service.UserService;
import io.quarkus.oidc.runtime.OidcJwtCallerPrincipal;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.UUID;

/**
 * Augments the security identity with tenant context after OIDC authentication.
 * Auto-provisions a tenant + org + membership on first login.
 *
 * Fully reactive — all service calls return Uni<T> and are chained
 * with flatMap. No runBlocking() or .await() calls.
 */
@ApplicationScoped
public class AutoOnboardingIdentityAugmentor implements SecurityIdentityAugmentor {

    private static final Logger LOG = Logger.getLogger(AutoOnboardingIdentityAugmentor.class);

    @Inject UserService              userService;
    @Inject UserOrganizationService  userOrganizationService;
    @Inject TenantService            tenantService;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context) {

        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        String externalId = identity.getPrincipal().getName();
        String email      = extractClaim(identity, "email");
        String name       = extractClaim(identity, "name");

        // Step 1 — find or create user
        return userService.findByExternalUserId(externalId)
                .flatMap((UserEntity existing) -> {
                    if (existing != null) {
                        return Uni.createFrom().item(existing);
                    }
                    LOG.infof("First login — creating user: %s", email);
                    return userService.createExternalUser(externalId, email, name);
                })

                // Step 2 — find or auto-provision tenant
                .flatMap((UserEntity user) ->
                        userOrganizationService.findTenantIdByUserId(user.userId)
                                .flatMap((UUID tenantId) -> {
                                    if (tenantId != null) {
                                        // Already has a tenant — skip provisioning
                                        return Uni.createFrom().item(
                                                new UserTenant(user, tenantId));
                                    }

                                    LOG.infof("First login — auto-provisioning tenant for: %s",
                                            email);

                                    String orgName = deriveOrgName(name, email);
                                    String idempotencyKey = "auto-tenant-" + user.userId;

                                    return tenantService.createTenant(orgName, idempotencyKey)
                                            .flatMap((UUID newTenantId) ->
                                                    tenantService.createDefaultOrganization(
                                                                    newTenantId, orgName)
                                                            .flatMap(org ->
                                                                    userOrganizationService
                                                                            .createMembership(
                                                                                    user.userId,
                                                                                    org.id,
                                                                                    newTenantId,
                                                                                    "OWNER")
                                                                            .invoke(m -> LOG.infof(
                                                                                    "Auto-provisioned tenant=%s org=%s user=%s",
                                                                                    newTenantId, org.id, email))
                                                                            .map(m -> new UserTenant(
                                                                                    user, newTenantId))
                                                            )
                                            );
                                })
                )

                // Step 3 — build augmented security identity
                .map((UserTenant ut) -> {
                    QuarkusSecurityIdentity.Builder builder =
                            QuarkusSecurityIdentity.builder(identity);

                    if (ut.user.isActive) {
                        Set<String> roles = Set.of("tenant_user");

                        builder.addCredential(new AuthenticatedIdentity(
                                ut.user.userId,
                                ut.tenantId,
                                ut.user.email,
                                ut.user.name != null ? ut.user.name : ut.user.email,
                                roles
                        ));

                        builder.addRole("tenant_user");
                        builder.addAttribute("userId",   ut.user.userId);
                        builder.addAttribute("tenantId", ut.tenantId);
                        builder.addAttribute("email",    ut.user.email);

                        LOG.debugf("Identity built: userId=%s tenantId=%s email=%s",
                                ut.user.userId, ut.tenantId, ut.user.email);
                    } else {
                        LOG.warnf("Inactive user attempted login: %s", email);
                    }

                    return (SecurityIdentity) builder.build();
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractClaim(SecurityIdentity identity, String claim) {
        if (identity.getPrincipal() instanceof OidcJwtCallerPrincipal oidc) {
            return oidc.getClaims().getClaimValueAsString(claim);
        }
        return null;
    }

    private String deriveOrgName(String name, String email) {
        if (name != null && !name.isBlank()) {
            return name + "'s Organization";
        }
        if (email != null && email.contains("@")) {
            return email.split("@")[0] + "'s Organization";
        }
        return "My Organization";
    }

    // ── Typed holder ─────────────────────────────────────────────────────────

    private static final class UserTenant {
        final UserEntity user;
        final UUID       tenantId;
        UserTenant(UserEntity user, UUID tenantId) {
            this.user     = user;
            this.tenantId = tenantId;
        }
    }
}