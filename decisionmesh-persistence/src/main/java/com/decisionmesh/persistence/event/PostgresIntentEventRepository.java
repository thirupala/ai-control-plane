package com.decisionmesh.persistence.event;

import com.decisionmesh.application.port.IntentEventRepositoryPort;
import com.decisionmesh.domain.event.DomainEvent;
import com.decisionmesh.persistence.entity.IntentEventEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
public class PostgresIntentEventRepository
        implements IntentEventRepositoryPort,
        PanacheRepository<IntentEventEntity> {

    @Override
    @Transactional
    public Uni<Void> appendAll(List<DomainEvent> events) {

        if (events == null || events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return Uni.createFrom().item(() -> {

            for (DomainEvent event : events) {

                IntentEventEntity entity = new IntentEventEntity();

                entity.eventId = event.eventId();
                entity.tenantId = event.tenantId();
                entity.aggregateId = event.aggregateId();
                entity.aggregateType = event.aggregateType();
                entity.version = event.version();
                entity.eventType = event.eventType().name();
                entity.payload = event.toJson();
                entity.occurredAt = event.occurredAt();

                persist(entity);   // ← Panache method
            }

            return null;
        }).replaceWithVoid();
    }
}
