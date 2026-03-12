package com.decisionmesh.application.service;

import com.decisionmesh.application.port.AdapterStats;
import com.decisionmesh.application.port.ExecutionRepositoryPort;
import com.decisionmesh.application.port.LearningEngine;
import com.decisionmesh.domain.execution.ExecutionRecord;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.hash.ReactiveHashCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class RedisLearningEngine implements LearningEngine {

    private static final String KEY_PREFIX = "learning:";

    private final ReactiveHashCommands<String, String, String> hash;

    @Inject
    public RedisLearningEngine(ReactiveRedisDataSource redis) {
        this.hash = redis.hash(String.class);
    }

    @Inject
    ExecutionRepositoryPort executionRepository;

    @Override
    public Uni<Void> updateProfiles(UUID intentId) {

        return executionRepository.findByIntentId(intentId)

                .flatMap(records -> {

                    if (records == null || records.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }

                    Map<String, MutableStats> aggregation = new HashMap<>();

                    for (ExecutionRecord record : records) {

                        aggregation
                                .computeIfAbsent(record.getAdapterId(), k -> new MutableStats())
                                .add(record);
                    }

                    return Uni.combine().all().unis(
                            aggregation.entrySet().stream()
                                    .map(entry -> {

                                        AdapterStats stats =
                                                entry.getValue().toAdapterStats();

                                        return persistProfile(
                                                intentId,
                                                entry.getKey(),
                                                stats
                                        );
                                    })
                                    .toList()
                    ).discardItems();
                });
    }

    private static class MutableStats {

        long attempts = 0;
        long successes = 0;
        double totalLatency = 0;
        double totalCost = 0;

        void add(ExecutionRecord record) {
            attempts++;
            totalLatency += record.getLatencyMs();
            totalCost += record.getCost();

            if (record.getFailureType() == null) {
                successes++;
            }
        }

        AdapterStats toAdapterStats() {

            if (attempts == 0) {
                return new AdapterStats(0, 0, 0);
            }

            double avgCost = totalCost / attempts;
            long avgLatency = Math.round(totalLatency / attempts);
            double failureRate = 1.0 - ((double) successes / attempts);

            return new AdapterStats(
                    avgCost,
                    avgLatency,
                    failureRate
            );
        }
    }


    private Uni<Void> persistProfile(UUID intentId,
                                     String adapterId,
                                     AdapterStats stats) {

        String key = KEY_PREFIX + intentId + ":" + adapterId;

        Map<String, String> data = new HashMap<>();

        data.put("avgCost", String.valueOf(stats.avgCost()));
        data.put("avgLatency", String.valueOf(stats.avgLatency()));
        data.put("failureRate", String.valueOf(stats.failureRate()));
        data.put("lastUpdated", Instant.now().toString());

        return hash.hset(key, data).replaceWithVoid();
    }




    @Override
    public Uni<Void> update(ExecutionRecord record) {

        String key = buildKey(record);

        return hash.hgetall(key)
                .onItem().transform(existing -> merge(existing, record))
                .onItem().transformToUni(updated ->
                        hash.hset(key, updated)
                )
                .replaceWithVoid();
    }

    private String buildKey(ExecutionRecord record) {
        return KEY_PREFIX +
                record.getIntentId() + ":" +
                record.getAdapterId();
    }

    private Map<String, String> merge(Map<String, String> existing,
                                      ExecutionRecord record) {

        Map<String, String> updated = new HashMap<>(existing);

        long attempts = Long.parseLong(existing.getOrDefault("attempts", "0"));
        long successes = Long.parseLong(existing.getOrDefault("successes", "0"));
        double avgLatency = Double.parseDouble(existing.getOrDefault("avgLatency", "0"));
        double avgCost = Double.parseDouble(existing.getOrDefault("avgCost", "0"));

        boolean success = record.getFailureType() == null;

        attempts++;

        if (success) {
            successes++;
        }

        // Incremental moving average
        avgLatency = ((avgLatency * (attempts - 1)) + record.getLatencyMs()) / attempts;
        avgCost = ((avgCost * (attempts - 1)) + record.getCost()) / attempts;

        updated.put("attempts", String.valueOf(attempts));
        updated.put("successes", String.valueOf(successes));
        updated.put("avgLatency", String.valueOf(avgLatency));
        updated.put("avgCost", String.valueOf(avgCost));
        updated.put("failureRate", String.valueOf(1.0 - ((double) successes / attempts)));
        updated.put("lastUpdated", Instant.now().toString());

        return updated;
    }
}
