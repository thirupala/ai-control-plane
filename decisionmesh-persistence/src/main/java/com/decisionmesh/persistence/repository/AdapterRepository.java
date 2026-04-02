package com.decisionmesh.persistence.repository;


import com.decisionmesh.persistence.entity.AdapterEntity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class AdapterRepository {

    public Uni<List<AdapterEntity>> findByTenant(UUID tenantId) {
        return AdapterEntity.findByTenant(tenantId);
    }

    public Uni<List<AdapterEntity>> findActiveByTenant(UUID tenantId) {
        return AdapterEntity.findActiveByTenant(tenantId);
    }

    public Uni<AdapterEntity> findById(UUID tenantId, UUID adapterId) {
        return AdapterEntity.findByTenantAndId(tenantId, adapterId);
    }

    public Uni<AdapterEntity> persist(AdapterEntity entity) {
        return entity.persist();
    }

    public Uni<Void> delete(AdapterEntity entity) {
        return entity.delete();
    }
}