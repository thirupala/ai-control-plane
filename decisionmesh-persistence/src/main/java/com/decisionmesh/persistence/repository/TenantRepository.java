package com.decisionmesh.persistence.repository;

import com.decisionmesh.persistence.entity.TenantEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TenantRepository implements PanacheRepository<TenantEntity> {
}
