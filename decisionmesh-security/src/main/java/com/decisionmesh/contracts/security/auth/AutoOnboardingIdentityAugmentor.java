package com.decisionmesh.contracts.security.auth;

import com.decisionmesh.contracts.security.entity.AuthenticatedIdentity;
import com.decisionmesh.contracts.security.entity.UserEntity;
import com.decisionmesh.contracts.security.service.ProjectService;
import com.decisionmesh.contracts.security.service.TenantService;
import com.decisionmesh.contracts.security.service.UserOrganizationService;
import com.decisionmesh.contracts.security.service.UserService;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.oidc.runtime.OidcJwtCallerPrincipal;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.UUID;

/**
 * Augments the security identity with tenant context after OIDC authentication.
 * Auto-provisions a tenant + org + membership + default project on first login.
 */
@ApplicationScoped
public class AutoOnboardingIdentityAugmentor implements SecurityIdentityAugmentor {

    private static final Logger LOG = Logger.getLogger(AutoOnboardingIdentityAugmentor.class);

    @Inject UserService              userService;
    @Inject UserOrganizationService  userOrganizationService;
    @Inject TenantService            tenantService;
    @Inject ProjectService           projectService;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context) {

        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        JsonWebToken jwt = (JsonWebToken) identity.getPrincipal();
        String keycloak_sub = jwt.getSubject();
        String email = extractClaim(identity, "email");
        String name = extractClaim(identity, "name");

        return Panache.withSession(() ->
                        userService.findByExternalUserId(keycloak_sub)
                                .onItem().ifNull().switchTo(() -> {
                                    LOG.infof("First login attempt — creating user: %s", email);
                                    return userService.createExternalUser(keycloak_sub, email, name)
                                            .onFailure().recoverWithUni(t -> userService.findByExternalUserId(keycloak_sub));
                                })
                                .onItem().ifNull().failWith(() -> new RuntimeException("User provisioning failed"))

                                .flatMap(user ->
                                        userOrganizationService.findTenantIdByUserId(user.userId)
                                                .flatMap(tenantId -> {
                                                    if (tenantId != null) {
                                                        // Fetch existing org name so it appears in UI
                                                        return tenantService.getOrganizationNameByTenantId(tenantId)
                                                                .map(orgName -> new UserTenant(user, tenantId, orgName));
                                                    }

                                                    LOG.infof("No membership found — auto-provisioning full stack for: %s", email);
                                                    String orgName = deriveOrgName(name, email);
                                                    String idempotencyKey = "auto-tenant-" + keycloak_sub;

                                                    return tenantService.createTenant(orgName, idempotencyKey)
                                                            .flatMap(newTenantId ->
                                                                    tenantService.createDefaultOrganization(newTenantId, orgName)
                                                                            .flatMap(org ->
                                                                                    userOrganizationService.createMembership(user.userId, org.id, newTenantId, "OWNER")
                                                                                            .flatMap(m -> projectService.initializeDefaultProject(newTenantId, user.userId)
                                                                                                    .replaceWith(new UserTenant(user, newTenantId, orgName)))
                                                                            )
                                                            );
                                                })
                                )
                )
                .map(result -> {
                    UserTenant ut = (UserTenant) result;
                    QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder(identity);

                    if (ut.user.isActive) {
                        builder.addCredential(new AuthenticatedIdentity(
                                ut.user.userId,
                                ut.tenantId,
                                ut.user.email,
                                ut.user.name != null ? ut.user.name : ut.user.email,
                                Set.of("tenant_user")
                        ));

                        builder.addRole("tenant_user");
                        builder.addAttribute("userId",   ut.user.userId);
                        builder.addAttribute("tenantId", ut.tenantId);
                        builder.addAttribute("email",    ut.user.email);

                        // CRITICAL: This allows the UI to display the real name instead of "My Organisation"
                        builder.addAttribute("orgName",  ut.orgName);

                        LOG.debugf("Identity built: userId=%s tenantId=%s orgName=%s",
                                ut.user.userId, ut.tenantId, ut.orgName);
                    }
                    return (SecurityIdentity) builder.build();
                });
    }

    private String extractClaim(SecurityIdentity identity, String claim) {
        if (identity.getPrincipal() instanceof OidcJwtCallerPrincipal oidc) {
            return oidc.getClaims().getClaimValueAsString(claim);
        }
        return null;
    }

    private String deriveOrgName(String name, String email) {
        if (name != null && !name.isBlank()) return name + "'s Organization";
        if (email != null && email.contains("@")) return email.split("@")[0] + "'s Organization";
        return "My Organization";
    }

    private static final class UserTenant {
        final UserEntity user;
        final UUID       tenantId;
        final String     orgName;

        UserTenant(UserEntity user, UUID tenantId, String orgName) {
            this.user     = user;
            this.tenantId = tenantId;
            this.orgName  = orgName;
        }
    }
}