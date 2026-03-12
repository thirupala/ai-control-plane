package com.decisionmesh.streaming.publisher;

import com.decisionmesh.streaming.outbox.OutboxEvent;
import com.decisionmesh.streaming.outbox.OutboxRepository;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;

import java.util.List;

public class KafkaOutboxPublisher {

    @Inject OutboxRepository repository;
    @Inject DomainEventPublisher publisher;

    public Uni<Void> publishPending() {
        return repository.findUnpublished()
                .onItem().transformToUni(events -> publishBatch(events));
    }

    private Uni<Void> publishBatch(List<OutboxEvent> events) {
        return Uni.combine().all().unis(
                events.stream()
                        .map(e -> publisher.publish(null)
                                .chain(() -> repository.markPublished(e)))
                        .toList()
        ).discardItems();
    }
}