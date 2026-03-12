package com.decisionmesh.persistence.repository;

import com.decisionmesh.persistence.entity.IntentEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class IntentRepository implements PanacheRepository<IntentEntity> {

    public Optional<IntentEntity> findByIdAndTenant(UUID id, String tenantId) {
        return find("id = :id and tenantId = :tenantId",
                Parameters.with("id", id)
                        .and("tenantId", tenantId))
                .firstResultOptional();
    }
}
