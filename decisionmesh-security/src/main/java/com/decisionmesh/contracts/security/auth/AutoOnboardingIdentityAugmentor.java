package com.decisionmesh.contracts.security.auth;

import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.contracts.security.entity.OrganizationEntity;
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

@ApplicationScoped
public class AutoOnboardingIdentityAugmentor implements SecurityIdentityAugmentor {

    private static final Logger LOG = Logger.getLogger(AutoOnboardingIdentityAugmentor.class);

    @Inject
    UserService userService;

    @Inject
    UserOrganizationService userOrganizationService;

    @Inject
    TenantService tenantService;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context) {

        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        return context.runBlocking(() -> {

            String externalId = identity.getPrincipal().getName();
            String email      = null;
            String name       = null;

            if (identity.getPrincipal() instanceof OidcJwtCallerPrincipal oidcPrincipal) {
                email = oidcPrincipal.getClaims().getClaimValueAsString("email");
                name  = oidcPrincipal.getClaims().getClaimValueAsString("name");
            }

            //  Step 1 — Find or create user
            UserEntity user = userService.findByExternalUserId(externalId);
            if (user == null) {
                LOG.infof("First login — creating user: %s", email);
                user = userService.createExternalUser(externalId, email, name);
            }

            //  Step 2 — Find or auto-provision tenant (industry standard)
            UUID tenantId = userOrganizationService
                    .findTenantIdByUserId(user.userId)
                    .orElse(null);

            if (tenantId == null) {
                LOG.infof("First login — auto-provisioning tenant for: %s", email);

                // Derive org name from user's name or email
                String orgName = (name != null && !name.isBlank())
                        ? name + "'s Organization"
                        : email.split("@")[0] + "'s Organization";

                // Idempotency key tied to user — safe to retry
                String idempotencyKey = "auto-tenant-" + user.userId;

                tenantId = tenantService.createTenant(orgName, idempotencyKey);

                OrganizationEntity org = tenantService.createDefaultOrganization(
                        tenantId, orgName
                );

                userOrganizationService.createMembership(
                        user.userId,
                        org.id,
                        tenantId,
                        "OWNER"
                );

                LOG.infof("Auto-provisioned tenant=%s org=%s for user=%s",
                        tenantId, org.id, email);
            }

            //  Step 3 — Build security identity
            QuarkusSecurityIdentity.Builder builder =
                    QuarkusSecurityIdentity.builder(identity);

            if (user.isActive) {

                Set<String> roles = Set.of("tenant_user");

                builder.addCredential(new AuthenticatedIdentity(
                        user.userId,
                        tenantId,       //  always populated now
                        user.email,
                        user.name != null ? user.name : user.email,
                        roles
                ));

                builder.addRole("tenant_user");
                builder.addAttribute("userId",   user.userId);
                builder.addAttribute("tenantId", tenantId);
                builder.addAttribute("email",    user.email);

                LOG.debugf("Identity built: userId=%s tenantId=%s email=%s",
                        user.userId, tenantId, user.email);

            } else {
                LOG.warnf("Inactive user attempted login: %s", email);
            }

            return builder.build();
        });
    }
}