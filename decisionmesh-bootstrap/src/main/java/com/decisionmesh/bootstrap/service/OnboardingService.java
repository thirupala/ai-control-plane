package com.decisionmesh.bootstrap.service;

import com.decisionmesh.bootstrap.resource.OnboardingResource.SetupTenantRequest;
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

    @ConfigProperty(name = "keycloak.admin.url")           String keycloakUrl;
    @ConfigProperty(name = "keycloak.admin.realm")         String realm;
    @ConfigProperty(name = "keycloak.admin.client-id")     String clientId;
    @ConfigProperty(name = "keycloak.admin.client-secret") String clientSecret;

    // =========================================================================
    // 1. PROVISION BASIC USER  (called from GET /me on every login)
    // =========================================================================

    @WithTransaction
    public Uni<UUID> provisionBasicUser(String keycloakSub, String email, String name) {

        LOG.infof("[Onboarding] /me: sub=%s email=%s", keycloakSub, email);

        UUID userId = UUID.fromString(keycloakSub);

        return Panache.withSession(() -> UserEntity.findByKeycloakSub(keycloakSub))
                .chain(existing -> {
                    if (existing != null) {
                        LOG.infof("[Onboarding] Existing user: userId=%s tenantId=%s",
                                existing.userId, existing.tenantId);
                        return Uni.createFrom().item(existing.tenantId);
                    }

                    LOG.infof("[Onboarding] New user — creating bare record: email=%s", email);

                    UserEntity user = new UserEntity();
                    user.userId   = userId;
                    user.email    = email;
                    user.name     = name;
                    user.isActive = true;

                    return userRepository.persist(user)
                            .invoke(saved -> LOG.infof(
                                    "[Onboarding] Bare user persisted: userId=%s", saved.userId))
                            .replaceWith((UUID) null);
                });
    }

    // =========================================================================
    // 2. SETUP TENANT  (called ONCE from POST /setup-tenant)
    // =========================================================================

    @WithTransaction
    public Uni<UUID> setupTenant(String keycloakSub, String email,
                                 String name, SetupTenantRequest req) {

        LOG.infof("[Onboarding] setupTenant: sub=%s accountType=%s",
                keycloakSub, req.accountType);

        return Panache.withSession(() -> UserEntity.findByKeycloakSub(keycloakSub))
                .chain(user -> {
                    if (user == null)
                        return Uni.createFrom().failure(new IllegalStateException(
                                "User not found for sub=" + keycloakSub + " — call /me first"));

                    if (user.tenantId != null)
                        return Uni.createFrom().failure(new IllegalStateException(
                                "Tenant already set up for this user"));

                    return "ORGANIZATION".equals(req.accountType)
                            ? doSetupOrganization(user, keycloakSub, req)
                            : doSetupIndividual(user, keycloakSub, name);
                });
    }

    // =========================================================================
    // PRIVATE — Individual setup
    // =========================================================================

    private Uni<UUID> doSetupIndividual(UserEntity user, String keycloakSub, String name) {

        LOG.infof("[Onboarding] INDIVIDUAL setup: userId=%s", user.userId);

        TenantEntity tenant = new TenantEntity();
        tenant.externalId   = keycloakSub;
        tenant.name         = name + "'s Workspace";
        tenant.accountType  = "INDIVIDUAL";
        tenant.status       = "ACTIVE";

        return tenantRepository.persist(tenant)
                .chain(savedTenant -> {

                    OrganizationEntity org = new OrganizationEntity();
                    org.name     = name + "'s Organization";
                    org.tenantId = savedTenant.id;
                    org.isActive = true;

                    OrgBrandingEntity branding = new OrgBrandingEntity();
                    branding.tenantId     = savedTenant.id;
                    branding.orgName      = org.name;
                    branding.primaryColor = "#2563eb";
                    branding.updatedAt    = Instant.now();

                    ProjectEntity project = new ProjectEntity();
                    project.tenantId    = savedTenant.id;
                    project.name        = "Default Project";
                    project.environment = "Production";
                    project.isDefault   = true;

                    return Uni.combine().all()
                            .unis(organizationRepository.persist(org),
                                    orgBrandingRepository.persist(branding),
                                    projectRepository.persist(project))
                            .asTuple()
                            .chain(tuple -> {

                                OrganizationEntity savedOrg     = tuple.getItem1();
                                ProjectEntity      savedProject = tuple.getItem3();

                                user.tenantId = savedTenant.id;

                                UserOrganizationEntity mapping = new UserOrganizationEntity();
                                mapping.userId         = user.userId;
                                mapping.organizationId = savedOrg.id;
                                mapping.tenantId       = savedTenant.id;
                                mapping.role           = "OWNER";
                                mapping.permissions    = List.of("ALL");
                                mapping.isActive       = true;

                                MemberShipEntity member = new MemberShipEntity();
                                member.tenantId     = savedTenant.id;
                                member.userId       = user.userId;
                                member.projectId    = savedProject.id;
                                member.role         = "ADMIN";
                                member.lastActiveAt = Instant.now();

                                return Uni.combine().all()
                                        .unis(userRepository.persist(user),
                                                userOrgRepository.persist(mapping),
                                                memberRepository.persist(member))
                                        .asTuple()
                                        .chain(t -> writeKeycloakAttributes(
                                                keycloakSub, savedTenant.id, "INDIVIDUAL"))
                                        .replaceWith(savedTenant.id)
                                        .invoke(id -> LOG.infof(
                                                "[Onboarding] ✅ INDIVIDUAL provisioned: tenantId=%s userId=%s",
                                                id, user.userId));
                            });
                });
    }

    // =========================================================================
    // PRIVATE — Organization setup
    // =========================================================================

    private Uni<UUID> doSetupOrganization(UserEntity user, String keycloakSub,
                                          SetupTenantRequest req) {

        LOG.infof("[Onboarding] ORGANIZATION setup: company=%s userId=%s",
                req.companyName, user.userId);

        return createKeycloakGroup(req.companyName)
                .chain(keycloakGroupId -> {

                    TenantEntity tenant    = new TenantEntity();
                    tenant.externalId      = keycloakSub;
                    tenant.name            = req.companyName;
                    tenant.accountType     = "ORGANIZATION";
                    tenant.keycloakGroupId = keycloakGroupId;
                    tenant.status          = "ACTIVE";

                    return tenantRepository.persist(tenant)
                            .chain(savedTenant -> {

                                OrganizationEntity org = new OrganizationEntity();
                                org.name        = req.companyName;
                                org.tenantId    = savedTenant.id;
                                org.companySize = req.companySize;
                                org.isActive    = true;

                                OrgBrandingEntity branding = new OrgBrandingEntity();
                                branding.tenantId     = savedTenant.id;
                                branding.orgName      = req.companyName;
                                branding.primaryColor = "#2563eb";
                                branding.updatedAt    = Instant.now();

                                ProjectEntity project = new ProjectEntity();
                                project.tenantId    = savedTenant.id;
                                project.name        = "Default Project";
                                project.environment = "Production";
                                project.isDefault   = true;

                                return Uni.combine().all()
                                        .unis(organizationRepository.persist(org),
                                                orgBrandingRepository.persist(branding),
                                                projectRepository.persist(project))
                                        .asTuple()
                                        .chain(tuple -> {

                                            OrganizationEntity savedOrg     = tuple.getItem1();
                                            ProjectEntity      savedProject = tuple.getItem3();

                                            user.tenantId = savedTenant.id;

                                            UserOrganizationEntity mapping = new UserOrganizationEntity();
                                            mapping.userId         = user.userId;
                                            mapping.organizationId = savedOrg.id;
                                            mapping.tenantId       = savedTenant.id;
                                            mapping.role           = "OWNER";
                                            mapping.permissions    = List.of("ALL");
                                            mapping.isActive       = true;

                                            MemberShipEntity member = new MemberShipEntity();
                                            member.tenantId     = savedTenant.id;
                                            member.userId       = user.userId;
                                            member.projectId    = savedProject.id;
                                            member.role         = "ADMIN";
                                            member.lastActiveAt = Instant.now();

                                            return Uni.combine().all()
                                                    .unis(userRepository.persist(user),
                                                            userOrgRepository.persist(mapping),
                                                            memberRepository.persist(member))
                                                    .asTuple()
                                                    .chain(t -> assignUserToKeycloakGroup(
                                                            keycloakSub, keycloakGroupId))
                                                    .chain(v -> writeKeycloakAttributes(
                                                            keycloakSub, savedTenant.id, "ORGANIZATION"))
                                                    .replaceWith(savedTenant.id)
                                                    .invoke(id -> LOG.infof(
                                                            "[Onboarding] ✅ ORGANIZATION provisioned: tenantId=%s userId=%s groupId=%s",
                                                            id, user.userId, keycloakGroupId));
                                        });
                            });
                });
    }

    // =========================================================================
    // PRIVATE — Keycloak: write tenantId + accountType as user attributes
    //
    // Changes from previous version:
    //   REMOVED: onFailure().recoverWithNull()  — was silently swallowing ALL errors
    //  ✅ ADDED:   full response body logged on every non-204
    //  ✅ ADDED:   admin token body logged on failure
    //  ✅ ADDED:   exception thrown with exact HTTP status + body message
    //  ✅ KEPT:    DB transaction never rolled back (Keycloak failure is non-fatal
    //              for the tenant record, but now always visible in logs)
    // =========================================================================

    private Uni<Void> writeKeycloakAttributes(String keycloakSub,
                                              UUID tenantId,
                                              String accountType) {

        LOG.infof("[Keycloak] Writing attributes: sub=%s tenantId=%s accountType=%s",
                keycloakSub, tenantId, accountType);

        WebClient client = WebClient.create(vertx);

        MultiMap form = MultiMap.caseInsensitiveMultiMap()
                .add("grant_type",    "client_credentials")
                .add("client_id",     clientId)
                .add("client_secret", clientSecret);

        return client
                .postAbs(keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token")
                .sendForm(form)
                .chain(tokenResponse -> {
                    if (tokenResponse.statusCode() != 200) {
                        String body = tokenResponse.bodyAsString();
                        LOG.errorf("[Keycloak]  Admin token FAILED: HTTP %d body=%s",
                                tokenResponse.statusCode(), body);
                        return Uni.createFrom().failure(new RuntimeException(
                                "Admin token failed: HTTP " + tokenResponse.statusCode() + " — " + body));
                    }

                    String adminToken = tokenResponse.bodyAsJsonObject().getString("access_token");
                    LOG.infof("[Keycloak] ✅ Admin token obtained");

                    // ── Step 1: GET current user data first ───────────────────────
                    // Required because Keycloak PUT replaces the whole user object.
                    // Without existing fields (email, firstName etc), Keycloak
                    // rejects with 400 "email is required".
                    return client
                            .getAbs(keycloakUrl + "/admin/realms/" + realm
                                    + "/users/" + keycloakSub)
                            .bearerTokenAuthentication(adminToken)
                            .send()
                            .chain(getRes -> {
                                if (getRes.statusCode() != 200) {
                                    LOG.errorf("[Keycloak]  GET user failed: HTTP %d body=%s",
                                            getRes.statusCode(), getRes.bodyAsString());
                                    return Uni.createFrom().failure(new RuntimeException(
                                            "GET user failed: HTTP " + getRes.statusCode()));
                                }

                                // ── Step 2: Merge attributes into existing user ────
                                JsonObject existingUser = getRes.bodyAsJsonObject();

                                // Get existing attributes (may be null)
                                JsonObject existingAttrs = existingUser.getJsonObject("attributes");
                                if (existingAttrs == null) existingAttrs = new JsonObject();

                                // Merge our new attributes into existing ones
                                existingAttrs
                                        .put("tenantId",    new JsonArray().add(tenantId.toString()))
                                        .put("accountType", new JsonArray().add(accountType));

                                // Build full user object with merged attributes
                                existingUser.put("attributes", existingAttrs);

                                LOG.infof("[Keycloak] Sending merged PUT for sub=%s", keycloakSub);
                                LOG.infof("[Keycloak] Attributes: tenantId=%s accountType=%s",
                                        tenantId, accountType);

                                // ── Step 3: PUT full user object back ─────────────
                                return client
                                        .putAbs(keycloakUrl + "/admin/realms/" + realm
                                                + "/users/" + keycloakSub)
                                        .bearerTokenAuthentication(adminToken)
                                        .putHeader("Content-Type", "application/json")
                                        .sendJsonObject(existingUser)
                                        .invoke(putRes -> {
                                            if (putRes.statusCode() == 204) {
                                                LOG.infof("[Keycloak] ✅ Attributes written: sub=%s tenantId=%s accountType=%s",
                                                        keycloakSub, tenantId, accountType);
                                            } else {
                                                LOG.errorf("[Keycloak]  PUT failed: HTTP %d body=%s",
                                                        putRes.statusCode(), putRes.bodyAsString());
                                                if (putRes.statusCode() == 403)
                                                    LOG.errorf("[Keycloak]  → 403: missing manage-users role on service account");
                                                if (putRes.statusCode() == 400)
                                                    LOG.errorf("[Keycloak]  → 400: %s",
                                                            putRes.bodyAsString());
                                            }
                                        })
                                        .replaceWithVoid();
                            });
                })
                .onFailure().invoke(e -> {
                    LOG.errorf("[Keycloak]  Exception: %s — %s",
                            e.getClass().getSimpleName(), e.getMessage());
                })
                .replaceWithVoid()
                .onFailure().recoverWithItem((Void) null);
    }

    // =========================================================================
    // PRIVATE — Keycloak: create group for ORGANIZATION
    // =========================================================================

    private Uni<String> createKeycloakGroup(String groupName) {

        LOG.infof("[Keycloak] Creating group: %s", groupName);

        WebClient client = WebClient.create(vertx);

        MultiMap form = MultiMap.caseInsensitiveMultiMap()
                .add("grant_type",    "client_credentials")
                .add("client_id",     clientId)
                .add("client_secret", clientSecret);

        return client
                .postAbs(keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token")
                .sendForm(form)
                .chain(tokenResponse -> {
                    if (tokenResponse.statusCode() != 200) {
                        String body = tokenResponse.bodyAsString();
                        LOG.errorf("[Keycloak]  Admin token fetch failed for group creation: HTTP %d body=%s",
                                tokenResponse.statusCode(), body);
                        return Uni.createFrom().failure(new RuntimeException(
                                "Admin token failed: HTTP " + tokenResponse.statusCode() + " — " + body));
                    }

                    String adminToken = tokenResponse.bodyAsJsonObject().getString("access_token");
                    JsonObject body   = new JsonObject().put("name", groupName);

                    return client
                            .postAbs(keycloakUrl + "/admin/realms/" + realm + "/groups")
                            .bearerTokenAuthentication(adminToken)
                            .putHeader("Content-Type", "application/json")
                            .sendJsonObject(body)
                            .map(res -> {
                                if (res.statusCode() != 201) {
                                    String resBody = res.bodyAsString();
                                    LOG.errorf("[Keycloak]  Group creation failed: HTTP %d body=%s",
                                            res.statusCode(), resBody);
                                    throw new RuntimeException(
                                            "Group creation failed: HTTP " + res.statusCode()
                                                    + " — " + resBody);
                                }
                                String location = res.getHeader("Location");
                                String groupId  = location.substring(location.lastIndexOf('/') + 1);
                                LOG.infof("[Keycloak]  Group created: %s → id=%s", groupName, groupId);
                                return groupId;
                            });
                });
    }

    // =========================================================================
    // PRIVATE — Keycloak: assign user to group (ORGANIZATION only)
    // =========================================================================

    private Uni<Void> assignUserToKeycloakGroup(String keycloakSub, String groupId) {

        LOG.infof("[Keycloak] Assigning user %s to group %s", keycloakSub, groupId);

        WebClient client = WebClient.create(vertx);

        MultiMap form = MultiMap.caseInsensitiveMultiMap()
                .add("grant_type",    "client_credentials")
                .add("client_id",     clientId)
                .add("client_secret", clientSecret);

        return client
                .postAbs(keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token")
                .sendForm(form)
                .chain(tokenResponse -> {
                    if (tokenResponse.statusCode() != 200) {
                        String body = tokenResponse.bodyAsString();
                        LOG.errorf("[Keycloak] Admin token failed for group assign: HTTP %d body=%s",
                                tokenResponse.statusCode(), body);
                        return Uni.createFrom().failure(new RuntimeException(
                                "Admin token failed: HTTP " + tokenResponse.statusCode() + " — " + body));
                    }

                    String adminToken = tokenResponse.bodyAsJsonObject().getString("access_token");

                    return client
                            .putAbs(keycloakUrl + "/admin/realms/" + realm
                                    + "/users/" + keycloakSub + "/groups/" + groupId)
                            .bearerTokenAuthentication(adminToken)
                            .putHeader("Content-Type", "application/json")
                            .send()
                            .invoke(res -> {
                                if (res.statusCode() == 204)
                                    LOG.infof("[Keycloak] User %s → group %s", keycloakSub, groupId);
                                else
                                    LOG.errorf("[Keycloak] Group assign failed: HTTP %d sub=%s group=%s body=%s",
                                            res.statusCode(), keycloakSub, groupId, res.bodyAsString());
                            })
                            .replaceWithVoid();
                })
                .onFailure().invoke(e -> LOG.errorf(
                        "[Keycloak] Group assignment exception: %s — %s",
                        e.getClass().getSimpleName(), e.getMessage()))
                .replaceWithVoid()
                .onFailure().recoverWithItem((Void) null);
    }

    // =========================================================================
    // PUBLIC — Repair Keycloak attributes for existing users
    // Called from POST /api/onboard/repair-attributes
    // Safe to call multiple times — idempotent
    // =========================================================================

    public Uni<Void> repairKeycloakAttributes(String keycloakSub) {

        LOG.infof("[Repair] Repairing attributes for sub=%s", keycloakSub);

        return Panache.withSession(() -> UserEntity.findByKeycloakSub(keycloakSub))
                .chain(rawUser -> {
                    if (rawUser == null)
                        return Uni.createFrom().failure(
                                new IllegalStateException("User not found for sub=" + keycloakSub));

                    UserEntity user = (UserEntity) rawUser;

                    if (user.tenantId == null)
                        return Uni.createFrom().failure(
                                new IllegalStateException("No tenant set up for sub=" + keycloakSub
                                        + " — complete onboarding first"));

                    return Panache.withSession(() ->
                                    TenantEntity.<TenantEntity>findById(user.tenantId))
                            .chain(tenant -> {
                                if (tenant == null)
                                    return Uni.createFrom().failure(
                                            new IllegalStateException(
                                                    "Tenant not found: " + user.tenantId));

                                String accountType = tenant.accountType != null
                                        ? tenant.accountType : "INDIVIDUAL";

                                LOG.infof("[Repair] Found tenant=%s accountType=%s — rewriting attributes",
                                        user.tenantId, accountType);

                                return writeKeycloakAttributes(keycloakSub, user.tenantId, accountType);
                            });
                });
    }
}