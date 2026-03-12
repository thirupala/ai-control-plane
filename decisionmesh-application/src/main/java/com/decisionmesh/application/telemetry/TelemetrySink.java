package com.decisionmesh.application.telemetry;

import io.smallrye.mutiny.Uni;

public interface TelemetrySink {
    Uni<Void> send(IntentTelemetryEvent event);
}
