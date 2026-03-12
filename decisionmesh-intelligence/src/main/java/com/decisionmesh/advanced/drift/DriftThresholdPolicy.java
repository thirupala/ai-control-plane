package com.decisionmesh.advanced.drift;

public record DriftThresholdPolicy(double threshold) {

    public boolean requiresReplan(double driftScore) {
        return driftScore >= threshold;
    }
}