package com.decisionmesh.bootstrap.service;

import com.decisionmesh.contracts.security.entity.*;
import com.decisionmesh.contracts.security.repository.*;
import com.decisionmesh.persistence.entity.OrgBrandingEntity;
import com.decisionmesh.persistence.repository.OrgBrandingRepository;
import com.decisionmesh.persistence.repository.TenantRepository;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.MultiMap;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OnboardingService {

    private static final Logger LOG = Logger.getLogger(OnboardingService.class);

    @Inject UserOrganizationRepository userOrgRepository;
    @Inject UserRepository             userRepository;
    @Inject OrganizationRepository     organizationRepository;
    @Inject TenantRepository           tenantRepository;
    @Inject OrgBrandingRepository      orgBrandingRepository;
    @Inject ProjectRepository          projectRepository;
    @Inject MemberShipRepository       memberRepository;
    @Inject Vertx                      vertx;

    @ConfigProperty(name = "keycloak.admin.url")    String keycloakUrl;
    @ConfigProperty(name = "keycloak.admin.realm")  String realm;
    @ConfigProperty(name = "keycloak.admin.client-id")     String clientId;
    @ConfigProperty(name = "keycloak.admin.client-secret") String clientSecret;

    // -------------------------------------------------------------------------
    // PUBLIC API
    // -------------------------------------------------------------------------

    @WithTransaction
    public Uni<Void> provisionNewUser(String externalId, String email, String name) {

        LOG.infof("[Onboarding] Request received: externalId=%s email=%s", externalId, email);

        return Panache.withSession(() -> TenantEntity.findByExternalId(externalId))
                .onItem().ifNotNull().transformToUni(existing -> {
                    LOG.infof("[Onboarding] Tenant already exists for externalId=%s — skipping (idempotent)",
                            externalId);
                    return Uni.createFrom().voidItem();
                })
                .onItem().ifNull().switchTo(() -> {
                    LOG.infof("[Onboarding] New user detected — provisioning full workspace: email=%s", email);
                    return doProvision(externalId, email, name);
                });
    }
    // -------------------------------------------------------------------------
    // PRIVATE — Full workspace provisioning
    // -------------------------------------------------------------------------

    private Uni<Void> doProvision(String externalId, String email, String name) {

        // ── 1. Tenant ─────────────────────────────────────────────────────────
        TenantEntity tenant = new TenantEntity();
        tenant.externalId = externalId;
        tenant.name       = name + "'s Tenant";
        tenant.status     = "ACTIVE";

        // ── 2. Organization ───────────────────────────────────────────────────
        OrganizationEntity org = new OrganizationEntity();
        org.name     = name + "'s Organization";
        org.isActive = true;

        // ── 3. User ───────────────────────────────────────────────────────────
        UserEntity user = new UserEntity();
        user.externalUserId = externalId;
        user.email          = email;
        user.name           = name;
        user.isActive       = true;

        return tenantRepository.persist(tenant)
                .invoke(saved -> LOG.infof("[Onboarding] Tenant persisted: id=%s name=%s",
                        saved.id, saved.name))

                .chain(savedTenant -> {
                    org.tenantId = savedTenant.id;

                    // Write tenantId + userId to Keycloak BEFORE continuing
                    // so that the token refresh in the UI gets fresh claims
                    return writeKeycloakAttributes(externalId, savedTenant.id, null)
                            .chain(() -> organizationRepository.persist(org))
                            .invoke(savedOrg -> LOG.infof(
                                    "[Onboarding] Organization persisted: id=%s name=%s tenantId=%s",
                                    savedOrg.id, savedOrg.name, savedTenant.id))

                            .chain(savedOrg -> {

                                OrgBrandingEntity branding = new OrgBrandingEntity();
                                branding.tenantId     = savedTenant.id;
                                branding.orgName      = savedOrg.name;
                                branding.primaryColor = "#2563eb";
                                branding.updatedAt    = Instant.now();

                                ProjectEntity project = new ProjectEntity();
                                project.tenantId    = savedTenant.id;
                                project.name        = "Default Project";
                                project.environment = "Production";
                                project.isDefault   = true;

                                LOG.infof("[Onboarding] Persisting user, branding and default project for tenantId=%s",
                                        savedTenant.id);

                                return Uni.combine().all().unis(
                                                userRepository.persist(user),
                                                orgBrandingRepository.persist(branding),
                                                projectRepository.persist(project)
                                        ).asTuple()

                                        .invoke(tuple -> LOG.infof(
                                                "[Onboarding] User persisted: userId=%s email=%s",
                                                tuple.getItem1().userId, email))

                                        .chain(tuple -> {

                                            UserEntity    savedUser    = tuple.getItem1();
                                            ProjectEntity savedProject = tuple.getItem3();

                                            // ── Write userId to Keycloak now that we have it ──
                                            return writeKeycloakAttributes(externalId, savedTenant.id, savedUser.userId)
                                                    .chain(() -> {

                                                        UserOrganizationEntity mapping = new UserOrganizationEntity();
                                                        mapping.userId         = savedUser.userId;
                                                        mapping.organizationId = savedOrg.id;
                                                        mapping.tenantId       = savedTenant.id;
                                                        mapping.role           = "OWNER";
                                                        mapping.permissions    = List.of("ALL");
                                                        mapping.isActive       = true;

                                                        return memberRepository
                                                                .findByTenantUserProject(
                                                                        savedTenant.id,
                                                                        savedUser.userId,
                                                                        savedProject.id)
                                                                .onItem().ifNull().switchTo(() -> {
                                                                    LOG.infof("[Onboarding] Creating membership: userId=%s projectId=%s role=ADMIN",
                                                                            savedUser.userId, savedProject.id);

                                                                    MemberShipEntity member = new MemberShipEntity();
                                                                    member.tenantId     = savedTenant.id;
                                                                    member.userId       = savedUser.userId;
                                                                    member.projectId    = savedProject.id;
                                                                    member.role         = "ADMIN";
                                                                    member.lastActiveAt = Instant.now();

                                                                    return memberRepository.persist(member);
                                                                })
                                                                .chain(() -> userOrgRepository.persist(mapping))
                                                                .invoke(() -> LOG.infof(
                                                                        "[Onboarding] ✅ Workspace provisioned successfully: " +
                                                                                "externalId=%s tenantId=%s userId=%s",
                                                                        externalId, savedTenant.id, savedUser.userId));
                                                    });
                                        });
                            });
                })
                .replaceWithVoid();
    }

    // -------------------------------------------------------------------------
    // PRIVATE — Keycloak attribute writeback
    // Writes tenantId and userId as user attributes so they appear in the JWT.
    // userId may be null on first call (before user is persisted) — it will
    // be written on the second call once the userId is known.
    // -------------------------------------------------------------------------

    private Uni<Void> writeKeycloakAttributes(String externalId, UUID tenantId, UUID userId) {

        LOG.infof("[Keycloak] Writing attributes: externalId=%s tenantId=%s userId=%s",
                externalId, tenantId, userId);

        WebClient client = WebClient.create(vertx);

        MultiMap form = MultiMap.caseInsensitiveMultiMap()
                .add("grant_type",    "client_credentials")
                .add("client_id",     clientId)
                .add("client_secret", clientSecret);

        return client
                .postAbs(keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token")
                .sendForm(form)

                .onItem().transformToUni(tokenResponse -> {

                    if (tokenResponse.statusCode() != 200) {
                        LOG.errorf("[Keycloak] Failed to obtain admin token: HTTP %d",
                                tokenResponse.statusCode());
                        return Uni.createFrom().voidItem();
                    }

                    String adminToken = tokenResponse.bodyAsJsonObject()
                            .getString("access_token");

                    LOG.debugf("[Keycloak] Admin token obtained — updating user attributes");

                    JsonObject attributes = new JsonObject()
                            .put("tenantId", new JsonArray().add(tenantId.toString()));

                    if (userId != null) {
                        attributes.put("userId", new JsonArray().add(userId.toString()));
                    }

                    JsonObject body = new JsonObject().put("attributes", attributes);

                    return client
                            .putAbs(keycloakUrl + "/admin/realms/" + realm + "/users/" + externalId)
                            .bearerTokenAuthentication(adminToken)
                            .putHeader("Content-Type", "application/json")
                            .sendJsonObject(body)
                            .invoke(res -> {
                                if (res.statusCode() == 204) {
                                    LOG.infof("[Keycloak] ✅ Attributes written: externalId=%s tenantId=%s userId=%s",
                                            externalId, tenantId, userId);
                                } else {
                                    LOG.warnf("[Keycloak] Attribute update returned HTTP %d for externalId=%s — body: %s",
                                            res.statusCode(), externalId, res.bodyAsString());
                                }
                            })
                            .replaceWithVoid();
                })

                .onFailure().invoke(e ->
                        LOG.errorf("[Keycloak] ❌ Failed to write attributes for externalId=%s: %s",
                                externalId, e.getMessage())
                )

                .replaceWithVoid()
                // Fail-safe — Keycloak write failure must NOT roll back DB provisioning
                .onFailure().recoverWithNull();
    }
}