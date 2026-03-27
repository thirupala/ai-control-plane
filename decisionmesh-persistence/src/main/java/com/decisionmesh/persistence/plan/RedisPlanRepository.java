package com.decisionmesh.persistence.plan;

import com.decisionmesh.application.port.PlanRepositoryPort;
import com.decisionmesh.domain.plan.Plan;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.sortedset.ReactiveSortedSetCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
@Alternative
@Priority(0)
@ApplicationScoped
public class RedisPlanRepository implements PlanRepositoryPort {

    private static final String PLAN_KEY_PREFIX = "plan:";
    private static final String PLAN_INDEX_PREFIX = "plan_index:";

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveSortedSetCommands<String, String> sortedSetCommands;

    @Inject
    public RedisPlanRepository(ReactiveRedisDataSource redis) {
        this.valueCommands = redis.value(String.class);
        this.sortedSetCommands = redis.sortedSet(String.class);
    }

    @Override
    public Uni<Void> save(Plan plan) {

        String planKey = buildPlanKey(
                plan.getIntentId(),
                plan.getVersion().value()
        );

        String indexKey = buildIndexKey(plan.getIntentId());

        return valueCommands
                .set(planKey, plan.toJson())
                .flatMap(v ->
                        sortedSetCommands.zadd(
                                indexKey,
                                plan.getVersion().value(),
                                planKey
                        )
                )
                .replaceWithVoid();
    }

    @Override
    public Uni<Plan> findLatestByIntentId(UUID intentId) {

        String indexKey = buildIndexKey(intentId);

        return sortedSetCommands
                .zrange(indexKey, 0, -1) // returns Set<String>
                .flatMap(keys -> {

                    if (keys == null || keys.isEmpty()) {
                        return Uni.createFrom().nullItem();
                    }

                    // highest version = last element
                    String latestKey = keys.stream()
                            .reduce((first, second) -> second)
                            .orElse(null);

                    if (latestKey == null) {
                        return Uni.createFrom().nullItem();
                    }

                    return valueCommands
                            .get(latestKey)
                            .map(Plan::fromJson);
                });
    }

    @Override
    public Uni<List<Plan>> findAllByIntentId(UUID intentId) {

        String indexKey = buildIndexKey(intentId);

        return sortedSetCommands
                .zrange(indexKey, 0, -1) // returns Set<String>
                .flatMap(keys -> {

                    if (keys == null || keys.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }

                    List<Uni<Plan>> planUnis = keys.stream()
                            .map(valueCommands::get)
                            .map(uni -> uni.map(Plan::fromJson))
                            .collect(Collectors.toList());

                    return Uni.combine().all().unis(planUnis)
                            .with(list -> list.stream()
                                    .map(obj -> (Plan) obj)
                                    .collect(Collectors.toList())
                            );
                });
    }

    private String buildPlanKey(UUID intentId, int version) {
        return PLAN_KEY_PREFIX + intentId + ":" + version;
    }

    private String buildIndexKey(UUID intentId) {
        return PLAN_INDEX_PREFIX + intentId;
    }
}
