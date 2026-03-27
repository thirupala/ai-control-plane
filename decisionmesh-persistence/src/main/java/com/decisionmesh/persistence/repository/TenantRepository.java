package com.decisionmesh.persistence.repository;

import com.decisionmesh.contracts.security.entity.TenantEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TenantRepository implements PanacheRepository<TenantEntity> {
}
