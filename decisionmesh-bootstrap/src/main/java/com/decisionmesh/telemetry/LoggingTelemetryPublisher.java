package com.decisionmesh.telemetry;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class LoggingTelemetryPublisher {

    public Uni<Void> publish(String phase, UUID tenantId, UUID intentId, long version) {
        Log.infof("Intent Telemetry: phase=%s, tenant=%s, intent=%s, version=%d",
                phase, tenantId, intentId, version);
        return Uni.createFrom().voidItem();
    }
}