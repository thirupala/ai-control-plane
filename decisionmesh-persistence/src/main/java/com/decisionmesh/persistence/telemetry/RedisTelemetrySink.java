package com.decisionmesh.persistence.telemetry;

import com.decisionmesh.application.telemetry.IntentTelemetryEvent;
import com.decisionmesh.application.telemetry.TelemetrySink;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.list.ReactiveListCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Objects;

@ApplicationScoped
public class RedisTelemetrySink implements TelemetrySink {

    private static final String KEY_PREFIX = "telemetry:";

    private final ReactiveListCommands<String, String> list;

    @Inject
    public RedisTelemetrySink(ReactiveRedisDataSource redis) {
        this.list = redis.list(String.class);
    }

    @Override
    public Uni<Void> send(IntentTelemetryEvent event) {

        Objects.requireNonNull(event, "Telemetry event cannot be null");

        String key = buildKey(event);

        return list.rpush(key, event.toJson())
                .replaceWithVoid();
    }

    private String buildKey(IntentTelemetryEvent event) {
        return KEY_PREFIX + event.getTenantId();
    }
}
