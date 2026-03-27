package com.decisionmesh.persistence.event;

import com.decisionmesh.application.port.IntentEventRepositoryPort;
import com.decisionmesh.domain.event.DomainEvent;
import com.decisionmesh.persistence.entity.IntentEventEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PostgresIntentEventRepository
        implements IntentEventRepositoryPort,
        PanacheRepository<IntentEventEntity> {

    public void appendAllSync(List<DomainEvent> events) {

        if (events == null || events.isEmpty()) {
            return;
        }

        for (DomainEvent event : events) {

            IntentEventEntity entity = new IntentEventEntity();

            entity.eventId       = event.eventId();
            entity.intentId      = event.aggregateId();
//            entity.aggregateId   = event.aggregateId();
            entity.aggregateType = event.aggregateType();
            entity.tenantId      = event.tenantId();
            entity.version       = event.version();
            entity.eventType     = event.eventType().name();
            entity.payload       = event.toJson();
            entity.occurredAt    = event.occurredAt();

            getEntityManager().persist(entity);
        }
    }

    @Override
    public Uni<Void> appendAll(List<DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom()
                .item(() -> { appendAllSync(events); return null; })
                .runSubscriptionOn(
                        io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool()
                )
                .replaceWithVoid();
    }
}