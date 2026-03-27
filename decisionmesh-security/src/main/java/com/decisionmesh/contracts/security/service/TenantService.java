package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.OrganizationEntity;
import com.decisionmesh.contracts.security.entity.TenantEntity;
import com.decisionmesh.contracts.security.entity.TenantIdempotencyEntity;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class TenantService {

    private static final Logger LOG = Logger.getLogger(TenantService.class);

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Derives a non-null org name from the provided value or falls back to a
     * name derived from the idempotency key (which is always present).
     * This prevents null constraint violations when the caller hasn't supplied
     * an org name (e.g. auto-onboarding from OIDC tokens without a name claim).
     */
    private String resolveOrgName(String organizationName, String idempotencyKey) {
        if (organizationName != null && !organizationName.isBlank()) {
            return organizationName;
        }
        // Derive from idempotency key — e.g. "auto-tenant-<uuid>" → "My Organization"
        if (idempotencyKey != null && idempotencyKey.startsWith("auto-tenant-")) {
            return "My Organization";
        }
        return idempotencyKey != null ? idempotencyKey : "Default Organization";
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Creates a new tenant, guarded by an idempotency key.
     * organizationName may be null — falls back to resolveOrgName().
     */
    public Uni<UUID> createTenant(String organizationName, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("Idempotency key required"));
        }

        final String resolvedName = resolveOrgName(organizationName, idempotencyKey);

        return Panache.withTransaction(() ->

                TenantIdempotencyEntity.findByKey(idempotencyKey)
                        .flatMap(existing -> {
                            if (existing != null) {
                                LOG.infof("createTenant: idempotent hit tenantId=%s key=%s",
                                        existing.tenantId, idempotencyKey);
                                return Uni.createFrom().item(existing.tenantId);
                            }

                            TenantEntity tenant = new TenantEntity();
                            tenant.externalId = idempotencyKey;
                            tenant.name       = resolvedName;
                            tenant.status     = "ACTIVE";

                            return tenant.<TenantEntity>persist()
                                    .flatMap(t -> {
                                        TenantIdempotencyEntity idem =
                                                TenantIdempotencyEntity.of(t.id, idempotencyKey);
                                        return idem.<TenantIdempotencyEntity>persist()
                                                .onFailure().recoverWithUni(ex ->
                                                        TenantIdempotencyEntity
                                                                .findByKey(idempotencyKey)
                                                                .map(race -> {
                                                                    if (race != null) return race;
                                                                    throw new RuntimeException(ex);
                                                                })
                                                )
                                                .map(i -> {
                                                    LOG.infof("createTenant: created tenantId=%s name=%s",
                                                            t.id, resolvedName);
                                                    return t.id;
                                                });
                                    });
                        })
        );
    }

    /**
     * Creates the default organization for a provisioned tenant.
     * organizationName may be null — falls back to resolveOrgName().
     *
     * Also backfills tenant.organizationId with the new org's ID.
     * Idempotent: returns existing org if already created under this tenant.
     */
    public Uni<OrganizationEntity> createDefaultOrganization(
            UUID tenantId, String organizationName) {

        final String resolvedName = resolveOrgName(organizationName, null);

        return Panache.withTransaction(() ->

                OrganizationEntity
                        .<OrganizationEntity>find(
                                "tenantId = ?1 and name = ?2 and isActive = true",
                                tenantId, resolvedName)
                        .firstResult()
                        .flatMap(existing -> {
                            if (existing != null) {
                                LOG.infof("createDefaultOrganization: already exists " +
                                        "orgId=%s tenantId=%s", existing.id, tenantId);
                                return Uni.createFrom().item(existing);
                            }

                            OrganizationEntity org = new OrganizationEntity();
                            org.tenantId = tenantId;
                            org.name     = resolvedName;
                            org.isActive = true;

                            return org.<OrganizationEntity>persist()
                                    .flatMap(savedOrg -> {
                                        LOG.infof("createDefaultOrganization: created " +
                                                        "orgId=%s tenantId=%s name=%s",
                                                savedOrg.id, tenantId, resolvedName);

                                        return TenantEntity
                                                .<TenantEntity>findById(tenantId)
                                                .flatMap(tenant -> {
                                                    if (tenant != null) {
                                                        tenant.organizationId = savedOrg.id;
                                                        return tenant.persist()
                                                                .replaceWith(savedOrg);
                                                    }
                                                    return Uni.createFrom().item(savedOrg);
                                                });
                                    });
                        })
        );
    }
}