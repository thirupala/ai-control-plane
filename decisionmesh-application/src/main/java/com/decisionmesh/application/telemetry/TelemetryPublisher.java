package com.decisionmesh.application.telemetry;

import com.decisionmesh.domain.intent.IntentPhase;
import io.smallrye.mutiny.Uni;

import java.util.UUID;

public interface TelemetryPublisher {
    Uni<Void> publish(IntentPhase eventType, UUID tenantId, UUID intentId, long version);
}