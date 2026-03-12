package com.decisionmesh.persistence.lock;

import com.decisionmesh.application.lock.LockContentionException;
import com.decisionmesh.application.lock.LockManager;
import com.decisionmesh.application.lock.LockToken;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;


@ApplicationScoped
public class RedisLockManager implements LockManager {

    private static final String LOCK_PREFIX = "lock:intent:";

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveKeyCommands<String> keyCommands;
    private final MeterRegistry meterRegistry;


    // Metrics
    private final Counter lockAcquiredCounter;
    private final Counter lockFailedCounter;
    private final Counter lockReleasedCounter;
    private final Counter lockExtendedCounter;
    private final Timer lockHoldTime;

    @ConfigProperty(name = "controlplane.lock.default-ttl-seconds", defaultValue = "30")
    int defaultTtlSeconds;

    @Inject
    public RedisLockManager(ReactiveRedisDataSource redis, MeterRegistry meterRegistry) {
        this.valueCommands = redis.value(String.class);
        this.keyCommands = redis.key();
        this.meterRegistry = meterRegistry;

        // Initialize metrics
        this.lockAcquiredCounter = Counter.builder("controlplane.lock.acquired")
                .description("Total locks successfully acquired")
                .register(meterRegistry);

        this.lockFailedCounter = Counter.builder("controlplane.lock.failed")
                .description("Total lock acquisition failures")
                .register(meterRegistry);

        this.lockReleasedCounter = Counter.builder("controlplane.lock.released")
                .description("Total locks successfully released")
                .register(meterRegistry);

        this.lockExtendedCounter = Counter.builder("controlplane.lock.extended")
                .description("Total locks successfully extended")
                .register(meterRegistry);

        this.lockHoldTime = Timer.builder("controlplane.lock.hold.duration")
                .description("Lock hold duration")
                .register(meterRegistry);
    }

    @Override
    public Uni<LockToken> acquire(String partitionKey, Duration ttl) {
        String key = LOCK_PREFIX + partitionKey;
        String token = UUID.randomUUID().toString();
        Instant acquiredAt = Instant.now();
        Instant expiresAt = acquiredAt.plus(ttl);

        SetArgs args = new SetArgs()
                .nx()  // Only set if not exists
                .px(ttl.toMillis());

        Log.debugf("Attempting to acquire lock: key=%s, token=%s, ttl=%dms",
                key, token, ttl.toMillis());

        return valueCommands.set(key, token, args)
                .onItem().transform(result -> {
                    // SetArgs.nx() returns null if key already exists
                    if (result == null) {
                        lockFailedCounter.increment();
                        Log.debugf("Lock acquisition failed (already held): key=%s", key);
                        throw new LockContentionException("Lock already held for partition: " + partitionKey);
                    }

                    lockAcquiredCounter.increment();
                    Log.infof("Lock acquired: key=%s, token=%s, expires=%s", key, token, expiresAt);

                    return new LockToken(partitionKey, token, acquiredAt, expiresAt);
                })
                .onFailure().invoke(failure -> {
                    lockFailedCounter.increment();
                    Log.errorf(failure, "Lock acquisition error: key=%s", key);
                });
    }

    @Override
    public Uni<LockToken> acquireWithRetry(String partitionKey, Duration ttl,
                                           int maxRetries, Duration initialBackoff) {
        return acquire(partitionKey, ttl)
                .onFailure(LockContentionException.class)
                .retry()
                .withBackOff(initialBackoff, Duration.ofSeconds(30))
                .atMost(maxRetries)
                .onFailure().invoke(failure ->
                        Log.warnf("Lock acquisition failed after %d retries: partition=%s",
                                maxRetries, partitionKey)
                );
    }

    @Override
    public Uni<Boolean> extend(LockToken token, Duration extension) {
        String key = LOCK_PREFIX + token.partitionKey();

        Log.debugf("Attempting to extend lock: key=%s, token=%s, extension=%dms",
                key, token.token(), extension.toMillis());

        // Get current token, verify it matches, then extend TTL
        return valueCommands.get(key)
                .onItem().transformToUni(currentToken -> {
                    if (currentToken == null) {
                        Log.warnf("Lock extension failed (expired): key=%s", key);
                        return Uni.createFrom().item(false);
                    }

                    if (!currentToken.equals(token.token())) {
                        Log.warnf("Lock extension failed (token mismatch): key=%s, expected=%s, got=%s",
                                key, token.token(), currentToken);
                        return Uni.createFrom().item(false);
                    }

                    // Token matches, extend the TTL
                    return keyCommands.pexpire(key, extension.toMillis())
                            .onItem().transform(extended -> {
                                if (Boolean.TRUE.equals(extended)) {
                                    lockExtendedCounter.increment();
                                    Log.infof("Lock extended: key=%s, token=%s, extension=%dms",
                                            key, token.token(), extension.toMillis());
                                    return true;
                                } else {
                                    Log.warnf("Lock extension pexpire failed: key=%s", key);
                                    return false;
                                }
                            });
                })
                .onFailure().invoke(failure ->
                        Log.errorf(failure, "Lock extension error: key=%s", key)
                )
                .onFailure().recoverWithItem(false);
    }

    @Override
    public Uni<Boolean> release(LockToken token) {
        String key = LOCK_PREFIX + token.partitionKey();

        Log.debugf("Attempting to release lock: key=%s, token=%s", key, token.token());

        // Get current token, verify it matches, then delete
        return valueCommands.get(key)
                .onItem().transformToUni(currentToken -> {
                    if (currentToken == null) {
                        Log.warnf("Lock release failed (already expired): key=%s", key);
                        return Uni.createFrom().item(false);
                    }

                    if (!currentToken.equals(token.token())) {
                        Log.warnf("Lock release failed (token mismatch): key=%s, expected=%s, got=%s",
                                key, token.token(), currentToken);
                        return Uni.createFrom().item(false);
                    }

                    // Token matches, delete the lock
                    return keyCommands.del(key)
                            .onItem().transform(deletedCount -> {
                                boolean released = deletedCount != null && deletedCount > 0;

                                if (released) {
                                    lockReleasedCounter.increment();

                                    // Record lock hold duration
                                    long holdDuration = System.currentTimeMillis() - token.acquiredAt().toEpochMilli();
                                    lockHoldTime.record(Duration.ofMillis(holdDuration));

                                    Log.infof("Lock released: key=%s, token=%s, held_for=%dms",
                                            key, token.token(), holdDuration);
                                }

                                return released;
                            });
                })
                .onFailure().invoke(failure ->
                        Log.errorf(failure, "Lock release error: key=%s", key)
                )
                .onFailure().recoverWithItem(false);
    }

    @Override
    public Uni<Boolean> isLocked(String partitionKey) {
        String key = LOCK_PREFIX + partitionKey;

        // exists() returns Uni<Boolean> in Quarkus 3.x
        return keyCommands.exists(key)
                .onItem().transform(exists -> Boolean.TRUE.equals(exists));
    }

    @Override
    public Uni<Boolean> forceRelease(String partitionKey) {
        String key = LOCK_PREFIX + partitionKey;

        Log.warnf("FORCE RELEASE (admin): key=%s", key);

        return keyCommands.del(key)
                .onItem().transform(count -> count != null && count > 0);
    }
}
