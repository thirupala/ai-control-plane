package com.decisionmesh.persistence.execution;

import com.decisionmesh.application.port.ExecutionRepositoryPort;
import com.decisionmesh.domain.execution.ExecutionRecord;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.list.ReactiveListCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RedisExecutionRepository implements ExecutionRepositoryPort {

    private static final String KEY_PREFIX = "execution:";

    private final ReactiveListCommands<String, String> listCommands;

    @Inject
    public RedisExecutionRepository(ReactiveRedisDataSource redis) {
        this.listCommands = redis.list(String.class);
    }

    @Override
    public Uni<Void> append(ExecutionRecord record) {

        String key = buildKey(record.getIntentId());

        return listCommands
                .rpush(key, record.toJson())
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> appendAll(List<ExecutionRecord> records) {

        if (records == null || records.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String key = buildKey(records.get(0).getIntentId());

        List<String> jsonRecords = records.stream()
                .map(ExecutionRecord::toJson)
                .toList();

        return listCommands.rpush(key, String.valueOf(jsonRecords)).replaceWithVoid();
    }

    @Override
    public Uni<List<ExecutionRecord>> findByIntentId(UUID intentId) {

        String key = buildKey(intentId);

        return listCommands
                .lrange(key, 0, -1)
                .map(list -> list.stream()
                        .map(ExecutionRecord::fromJson)
                        .toList());
    }

    private String buildKey(UUID intentId) {
        return KEY_PREFIX + intentId;
    }
}
