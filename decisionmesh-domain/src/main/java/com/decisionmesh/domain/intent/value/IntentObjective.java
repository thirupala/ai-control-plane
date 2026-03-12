package com.decisionmesh.domain.intent.value;

/**
 * @param objectiveType COST, LATENCY, RISK, QUALITY
 */
public record IntentObjective(ObjectiveType objectiveType, double targetThreshold, double tolerance) {

    public static IntentObjective of(ObjectiveType type,
                                     double targetThreshold,
                                     double tolerance) {
        return new IntentObjective(type, targetThreshold, tolerance);
    }
}