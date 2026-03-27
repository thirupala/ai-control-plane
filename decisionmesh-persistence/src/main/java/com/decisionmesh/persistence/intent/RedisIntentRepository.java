package com.decisionmesh.persistence.intent;

import com.decisionmesh.application.port.IntentRepositoryPort;
import com.decisionmesh.domain.intent.Intent;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.NotFoundException;

import java.util.Objects;
import java.util.UUID;

import static io.quarkus.hibernate.orm.panache.Panache.getEntityManager;

/**
 * Redis-backed Intent repository.
 *
 * <p>Intent-centric design:</p>
 * <ul>
 *     <li>Partitioned by tenant + intentId</li>
 *     <li>Optimistic version handling</li>
 *     <li>Aggregate stored as single JSON blob</li>
 * </ul>
 */
@Alternative
@Priority(0)
@ApplicationScoped
public class RedisIntentRepository implements IntentRepositoryPort {

    private static final String KEY_PREFIX = "intent:";

    private final ReactiveValueCommands<String, String> valueCommands;

    @Inject
    public RedisIntentRepository(ReactiveRedisDataSource redis) {

        this.valueCommands = redis.value(String.class);
    }

    @Override
    public Uni<Void> save(Intent intent) {

        Objects.requireNonNull(intent, "Intent cannot be null");

        String key = buildKey(intent);

            return valueCommands.get(key)
                .flatMap(existingJson -> {

                    if (existingJson != null) {

                        Intent existingIntent = Intent.fromJson(existingJson);

                        if (existingIntent.getVersion() >= intent.getVersion()) {
                            return Uni.createFrom().failure(
                                    new OptimisticLockException(
                                            "Version conflict for intent " + intent.getId()
                                    )
                            );
                        }
                    }

                    return valueCommands
                            .set(key, intent.toJson())
                            .replaceWithVoid();
                });
    }


    @Override
    public Uni<Intent> findById(UUID tenantId, UUID intentId) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(intentId);

        String key = String.format("%s%s:%s", KEY_PREFIX, tenantId, intentId);

        return valueCommands.get(key)
                .onItem().ifNull().failWith(
                        new NotFoundException(
                                String.format("Intent not found: tenant=%s, id=%s",
                                        tenantId, intentId)
                        )
                )
                .map(Intent::fromJson);
    }

    @Override
    public Uni<Void> flush() {
        return Uni.createFrom().item(() -> {
            getEntityManager().flush();
            return null;
        });
    }


    private String buildKey(Intent intent) {
        return KEY_PREFIX +
                intent.getTenantId() +
                ":" +
                intent.getId();
    }
}
