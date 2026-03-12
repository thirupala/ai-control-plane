package com.decisionmesh.plan.ranking;

import java.util.Map;

public record RankingSnapshot(Map<String, Double> adapterScores) {

    public RankingSnapshot(Map<String, Double> adapterScores) {
        this.adapterScores = Map.copyOf(adapterScores);
    }
}