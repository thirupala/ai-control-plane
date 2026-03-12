package com.decisionmesh.eventsourcing.outbox;

import io.smallrye.mutiny.Uni;

public interface OutboxPublisher {

    Uni<Void> publishPending();

}