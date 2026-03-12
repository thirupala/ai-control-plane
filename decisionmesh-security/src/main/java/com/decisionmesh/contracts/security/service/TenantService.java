package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.Organization;
import com.decisionmesh.contracts.security.entity.Tenant;
import com.decisionmesh.contracts.security.entity.TenantIdempotency;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class TenantService {

    @Transactional
    public UUID createTenant(String organizationName, String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key required");
        }

        // 1️⃣ Check existing idempotency record
        TenantIdempotency existing =
                TenantIdempotency.find("idempotencyKey", idempotencyKey)
                        .firstResult();

        if (existing != null) {
            return existing.tenantId;
        }

        // 2️⃣ Create new tenant
        UUID tenantId = UUID.randomUUID();

        Tenant tenant = new Tenant();
        tenant.tenantId = tenantId;
        tenant.organizationName = organizationName;
        tenant.createdAt = Instant.now();
        tenant.active = true;
        tenant.persist();

        // 3️⃣ Store idempotency record
        TenantIdempotency idem = new TenantIdempotency();
        idem.id = UUID.randomUUID();
        idem.idempotencyKey = idempotencyKey;
        idem.tenantId = tenantId;
        idem.createdAt = Instant.now();

        try {
            idem.persistAndFlush();
        } catch (PersistenceException e) {
            // 4️⃣ Handle race condition (concurrent same key)
            TenantIdempotency race =
                    TenantIdempotency.find("idempotencyKey", idempotencyKey)
                            .firstResult();

            if (race != null) {
                return race.tenantId;
            }
            throw e;
        }

        return tenantId;
    }

    // ===============================
    // DEFAULT ORGANIZATION
    // ===============================

    @Transactional
    public Organization createDefaultOrganization(
            UUID tenantId,
            String organizationName
    ) {
        Organization org = new Organization();
        org.organizationId = UUID.randomUUID();
        org.tenantId = tenantId;
        org.name = organizationName;
        org.createdAt = Instant.now();
        org.active = true;
        org.persist();
        return org;
    }
}
