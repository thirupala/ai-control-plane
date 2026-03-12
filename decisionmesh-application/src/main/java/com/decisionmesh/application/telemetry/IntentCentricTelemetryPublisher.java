package com.decisionmesh.application.telemetry;

import com.decisionmesh.domain.intent.IntentPhase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class IntentCentricTelemetryPublisher implements TelemetryPublisher {

    private final TelemetrySink sink;

    @Inject
    public IntentCentricTelemetryPublisher(TelemetrySink sink) {
        this.sink = sink;
    }

    @Override
    public Uni<Void> publish(IntentPhase phase,
                             UUID tenantId,
                             UUID intentId,
                             long version) {

        IntentTelemetryEvent event =
                new IntentTelemetryEvent(
                        tenantId,
                        intentId,
                        version,
                        phase
                );

        return sink.send(event);
    }
}
