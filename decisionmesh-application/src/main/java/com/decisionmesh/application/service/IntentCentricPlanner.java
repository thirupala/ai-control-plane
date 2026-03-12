package com.decisionmesh.application.service;

import com.decisionmesh.application.port.AdapterStats;
import com.decisionmesh.application.port.Planner;
import com.decisionmesh.application.port.AdapterLearningPort;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.intent.value.ObjectiveType;
import com.decisionmesh.domain.plan.Plan;
import com.decisionmesh.domain.plan.PlanStrategy;
import com.decisionmesh.domain.value.PlanVersion;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class IntentCentricPlanner implements Planner {

    private final AdapterLearningPort learningPort;

    @Inject
    public IntentCentricPlanner(AdapterLearningPort learningPort) {
        this.learningPort = learningPort;
    }

    @Override
    public Uni<Plan> plan(Intent intent) {

        List<String> candidates =
                List.copyOf(intent.getConstraints().allowedAdapters());

        return learningPort
                .getStats(intent.getId(), candidates)
                .onItem()
                .transform(stats -> selectBest(intent, stats));
    }

    private Plan selectBest(Intent intent,
                            Map<String, AdapterStats> stats) {

        ObjectiveType objective =
                intent.getObjective().objectiveType();

        Comparator<Map.Entry<String, AdapterStats>> comparator =
                Comparator.comparingDouble(entry ->
                        score(objective, entry.getValue())
                );

        String bestAdapter = stats.entrySet()
                .stream()
                .min(comparator)
                .orElseThrow()
                .getKey();

        return Plan.create(
                intent.getId(),
                PlanVersion.initial(),
                PlanStrategy.SINGLE_ADAPTER,
                List.of(bestAdapter),
                stats.get(bestAdapter).avgCost(),
                stats.get(bestAdapter).avgLatency(),
                1.0
        );
    }

    private double score(ObjectiveType objective,
                         AdapterStats stats) {

        double failurePenalty = stats.failureRate() * 10;
        double costPenalty = stats.avgCost();
        double latencyPenalty = stats.avgLatency() / 1000.0;

        return switch (objective) {

            case COST ->
                    costPenalty + failurePenalty;

            case LATENCY ->
                    latencyPenalty + failurePenalty;

            case QUALITY ->
                    failurePenalty;

            case RISK ->
                    failurePenalty * 2;

        };
    }
}
