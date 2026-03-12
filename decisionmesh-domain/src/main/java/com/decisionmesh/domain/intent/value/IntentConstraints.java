package com.decisionmesh.domain.intent.value;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * @param maxCost            Economic constraints
 * @param maxLatency         Performance constraints
 * @param maxRiskScore       Risk constraints
 * @param maxRetries         Execution constraints
 * @param allowedRegions     Routing constraints
 * @param minConfidenceScore Quality constraints
 * @param maxDriftScore      Drift tolerance
 */
public record IntentConstraints(double maxCost, int maxTokens, Duration maxLatency, Instant deadline,
                                double maxRiskScore, int maxRetries, boolean allowParallelExecution,
                                Set<String> allowedRegions, Set<String> allowedAdapters, double minConfidenceScore,
                                double maxDriftScore, double maxDriftThreshold, Duration maxExecutionWindow) {

    public IntentConstraints(
            double maxCost,
            int maxTokens,
            Duration maxLatency,
            Instant deadline,
            double maxRiskScore,
            int maxRetries,
            boolean allowParallelExecution,
            Set<String> allowedRegions,
            Set<String> allowedAdapters,
            double minConfidenceScore,
            double maxDriftScore,
            double maxDriftThreshold,
            Duration maxExecutionWindow
    ) {

        if (maxCost < 0) throw new IllegalArgumentException("maxCost must be >= 0");
        if (maxTokens < 0) throw new IllegalArgumentException("maxTokens must be >= 0");
        if (maxRiskScore < 0) throw new IllegalArgumentException("maxRiskScore must be >= 0");
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
        if (minConfidenceScore < 0) throw new IllegalArgumentException("minConfidenceScore must be >= 0");
        if (maxDriftScore < 0) throw new IllegalArgumentException("maxDriftScore must be >= 0");
        if (maxDriftThreshold < 0) throw new IllegalArgumentException("maxDriftThreshold must be >= 0");

        this.maxCost = maxCost;
        this.maxTokens = maxTokens;
        this.maxLatency = maxLatency;
        this.deadline = deadline;
        this.maxRiskScore = maxRiskScore;
        this.maxRetries = maxRetries;
        this.allowParallelExecution = allowParallelExecution;

        // Defensive copy
        this.allowedRegions = allowedRegions == null
                ? Collections.emptySet()
                : Set.copyOf(allowedRegions);

        this.allowedAdapters = allowedAdapters == null
                ? Collections.emptySet()
                : Set.copyOf(allowedAdapters);

        this.minConfidenceScore = minConfidenceScore;
        this.maxDriftScore = maxDriftScore;
        this.maxDriftThreshold = maxDriftThreshold;
        this.maxExecutionWindow = maxExecutionWindow;
    }

    // =====================================
    // Factory (cleaned and safe)
    // =====================================

    public static IntentConstraints of(
            double maxCost,
            Duration maxLatency,
            double maxRiskScore,
            int maxRetries,
            Set<String> allowedRegions,
            Set<String> allowedAdapters
    ) {

        Objects.requireNonNull(maxLatency, "maxLatency required");

        Duration executionWindow = maxLatency.multipliedBy(3);

        return new IntentConstraints(
                maxCost,
                10000,                               // default maxTokens
                maxLatency,
                Instant.now().plus(maxLatency),      // auto deadline
                maxRiskScore,
                maxRetries,
                false,                               // default parallel execution
                allowedRegions,
                allowedAdapters,
                0.5,                                 // default confidence
                1.0,                                 // default drift score
                1.0,                                 // default drift threshold
                executionWindow
        );
    }

    // =====================================
    // Getters
    // =====================================
}
