package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.OrganizationEntity;
import com.decisionmesh.contracts.security.entity.TenantEntity;
import com.decisionmesh.contracts.security.entity.TenantIdempotencyEntity;
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

        //  Check existing idempotency record
        TenantIdempotencyEntity existing =
                TenantIdempotencyEntity.find("idempotencyKey", idempotencyKey)
                        .firstResult();

        if (existing != null) {
            return existing.tenantId;
        }

        //  Create new tenant using correct TenantEntity
        TenantEntity tenant   = new TenantEntity();
        tenant.id=UUID.randomUUID();
        tenant.externalId     = idempotencyKey;          //  was tenant.tenantId
        tenant.name           = organizationName;         //  was tenant.organizationName
        tenant.organizationId = UUID.randomUUID();        //  placeholder — updated when org created
        tenant.status         = "ACTIVE";                 //  was tenant.active = true
        tenant.config         = "{}";
        tenant.createdAt      = Instant.now();
        tenant.updatedAt      = Instant.now();            //  was missing
        tenant.persist();

        //  Store idempotency record
        TenantIdempotencyEntity idem = new TenantIdempotencyEntity();
        idem.id             = UUID.randomUUID();
        idem.idempotencyKey = idempotencyKey;
        idem.tenantId       = tenant.id;                  //  use generated id not UUID.randomUUID()
        idem.createdAt      = Instant.now();

        try {
            idem.persistAndFlush();
        } catch (PersistenceException e) {
            //  Handle race condition
            TenantIdempotencyEntity race =
                    TenantIdempotencyEntity.find("idempotencyKey", idempotencyKey)
                            .firstResult();
            if (race != null) {
                return race.tenantId;
            }
            throw e;
        }

        return tenant.id;                                  //  return generated id
    }

    @Transactional
    public OrganizationEntity createDefaultOrganization(UUID tenantId, String organizationName) {
        OrganizationEntity org = new OrganizationEntity();
        org.id        = UUID.randomUUID();          //  was org.organizationId
        org.tenantId  = tenantId;
        org.name      = organizationName;
        org.config    = "{}";                       //  NOT NULL in DB
        org.isActive  = true;                       //  was org.active
        org.createdAt = Instant.now();
        org.updatedAt = Instant.now();              //  NOT NULL in DB
        org.persist();

        //  Update tenant.organizationId now that org exists
        TenantEntity tenant = TenantEntity.findById(tenantId);
        if (tenant != null) {
            tenant.organizationId = org.id;
            tenant.updatedAt      = Instant.now();
        }

        return org;
    }
}