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

/**
 * Redis-backed Intent repository.
 *
 * Intent-centric design:
 *   - Partitioned by tenant + intentId
 *   - Optimistic version conflict detection
 *   - Aggregate stored as single JSON blob
 *
 * flush() is a no-op here — Redis writes are synchronous per-command.
 * There is no session or transaction to flush.
 * The blocking getEntityManager().flush() has been removed.
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
                                            "Version conflict for intent " + intent.getId()));
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
                        new NotFoundException(String.format(
                                "Intent not found: tenant=%s, id=%s", tenantId, intentId)))
                .map(Intent::fromJson);
    }

    /**
     * No-op for Redis — each set() call is immediately durable.
     * There is no Hibernate session or JPA transaction to flush.
     */
    @Override
    public Uni<Void> flush() {
        return Uni.createFrom().voidItem();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildKey(Intent intent) {
        return KEY_PREFIX + intent.getTenantId() + ":" + intent.getId();
    }
}