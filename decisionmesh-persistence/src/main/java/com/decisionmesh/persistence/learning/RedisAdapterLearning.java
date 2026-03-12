package com.decisionmesh.persistence.learning;


import com.decisionmesh.application.port.AdapterLearningPort;
import com.decisionmesh.application.port.AdapterStats;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.hash.ReactiveHashCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class RedisAdapterLearning implements AdapterLearningPort {

    private static final String KEY_PREFIX = "learning:";

    private final ReactiveHashCommands<String, String, String> hash;

    @Inject
    public RedisAdapterLearning(ReactiveRedisDataSource redis) {
        this.hash = redis.hash(String.class);
    }

    @Override
    public Uni<Map<String, AdapterStats>> getStats(UUID intentId,
                                                   List<String> adapters) {

        List<Uni<Map.Entry<String, AdapterStats>>> calls =
                adapters.stream()
                        .map(adapter ->
                                fetch(intentId, adapter)
                                        .map(stats -> Map.entry(adapter, stats))
                        )
                        .toList();

        return Uni.combine()
                .all()
                .unis(calls)
                .with(results ->
                        results.stream()
                                .map(obj -> (Map.Entry<String, AdapterStats>) obj)
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        Map.Entry::getValue
                                ))
                );
    }


    private Uni<AdapterStats> fetch(UUID intentId,
                                    String adapterId) {

        String key = KEY_PREFIX + intentId + ":" + adapterId;

        return hash.hgetall(key)
                .onItem()
                .transform(data -> {

                    if (data == null || data.isEmpty()) {
                        return defaultStats();
                    }

                    double avgCost =
                            Double.parseDouble(data.getOrDefault("avgCost", "0"));

                    long avgLatency =
                            (long) Double.parseDouble(
                                    data.getOrDefault("avgLatency", "0")
                            );

                    double failureRate =
                            Double.parseDouble(
                                    data.getOrDefault("failureRate", "0")
                            );

                    return new AdapterStats(
                            avgCost,
                            avgLatency,
                            failureRate
                    );
                });
    }

    private AdapterStats defaultStats() {
        return new AdapterStats(
                0.0,      // avgCost
                0L,       // avgLatency
                0.0       // failureRate
        );
    }
}
