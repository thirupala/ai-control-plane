package com.decisionmesh.persistence.repository;

import com.decisionmesh.persistence.entity.IntentEventEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class IntentEventRepository implements PanacheRepository<IntentEventEntity> {
}