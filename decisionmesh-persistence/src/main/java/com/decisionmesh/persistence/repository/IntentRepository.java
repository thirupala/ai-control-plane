package com.decisionmesh.persistence.repository;

import com.decisionmesh.persistence.entity.IntentEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/**
 * Reactive Panache repository for IntentEntity.
 *
 * Fixed:
 *   - PanacheRepository (blocking) → PanacheRepositoryBase<IntentEntity, UUID> (reactive)
 *   - findByIdAndTenant() returned Optional (blocking) → Uni<IntentEntity> (reactive)
 *   - tenantId was String → UUID (matches IntentEntity.tenantId field type)
 *   - Parameters.with() JPQL named params → positional params (?1, ?2)
 *     (named params via Parameters require the blocking PanacheRepository API
 *      and cause "cannot find symbol: method find(String, Parameters)" in reactive)
 *   - Removed static import of PanacheEntityBase.find — repository inherits
 *     find() from PanacheRepositoryBase directly
 */
@ApplicationScoped
public class IntentRepository implements PanacheRepositoryBase<IntentEntity, UUID> {

    /**
     * Finds an intent by its ID scoped to a tenant.
     * Returns null inside Uni if not found.
     */
    public Uni<IntentEntity> findByIdAndTenant(UUID id, UUID tenantId) {
        return find("id = ?1 and tenantId = ?2", id, tenantId).firstResult();
    }
}