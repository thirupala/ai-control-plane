package com.decisionmesh.application.service;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.application.port.ExecutionRecordQueryPort;
import com.decisionmesh.application.port.ExecutionRecordQueryPort.DriftRow;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Implements computeDriftScore() — previously returning BigDecimal.ZERO with a TODO.
 *
 * Uses ExecutionRecordQueryPort.findRecentByAdapter() — the port interface
 * defined in decisionmesh-application, implemented by ExecutionRecordRepository
 * in decisionmesh-infrastructure. Never imports infrastructure directly.
 *
 * Module:  decisionmesh-intelligence
 * Package: com.decisionmesh.intelligence.drift
 *
 * Drift dimensions (weights):
 *   cost drift     0.30  — z-score vs adapter baseline
 *   latency drift  0.25  — z-score vs adapter baseline
 *   quality drift  0.30  — drop below baseline mean quality
 *   failure drift  0.15  — recent failure rate vs 30-day baseline
 */
@ApplicationScoped
public class DriftEvaluatorService {

    private static final Logger LOG = Logger.getLogger(DriftEvaluatorService.class);

    private static final int    BASELINE_DAYS        = 30;
    private static final int    MIN_BASELINE_RECORDS = 5;
    private static final double COST_WEIGHT          = 0.30;
    private static final double LATENCY_WEIGHT       = 0.25;
    private static final double QUALITY_WEIGHT       = 0.30;
    private static final double FAILURE_WEIGHT       = 0.15;

    @Inject
    ExecutionRecordQueryPort executionRecordQueryPort;   // port — never depends on infrastructure directly

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a drift score in [0.0, 1.0] for the given execution.
     * 0.0 = normal, 1.0 = extreme deviation from adapter baseline.
     *
     * Called from ControlPlaneOrchestrator in the EVALUATING phase.
     */
    public Uni<BigDecimal> computeDriftScore(Intent intent, ExecutionRecord current) {
        String adapterId = current.getAdapterId();
        if (adapterId == null || adapterId.isBlank()) {
            LOG.debug("No adapter ID on execution — drift score = 0");
            return Uni.createFrom().item(BigDecimal.ZERO);
        }

        Instant since = Instant.now().minus(BASELINE_DAYS, ChronoUnit.DAYS);

        return executionRecordQueryPort
                .findRecentByAdapter(adapterId, since)
                .map(baseline -> {
                    if (baseline.size() < MIN_BASELINE_RECORDS) {
                        LOG.debugf("Insufficient baseline for adapter %s (%d records) — drift=0",
                                adapterId, baseline.size());
                        return BigDecimal.ZERO;
                    }
                    double drift = calculate(current, baseline);
                    BigDecimal result = BigDecimal.valueOf(drift).setScale(4, RoundingMode.HALF_UP);
                    LOG.debugf("Drift for intent %s adapter %s = %.4f",
                            intent.getId(), adapterId, drift);
                    return result;
                })
                .onFailure().recoverWithItem(ex -> {
                    LOG.warnf("Drift calculation failed for intent %s: %s",
                            intent.getId(), ex.getMessage());
                    return BigDecimal.ZERO;
                });
    }

    // ── Calculation ───────────────────────────────────────────────────────────

    private double calculate(ExecutionRecord current, List<DriftRow> baseline) {
        double costDrift    = computeCostDrift(current, baseline);
        double latencyDrift = computeLatencyDrift(current, baseline);
        double qualityDrift = computeQualityDrift(current, baseline);
        double failureDrift = computeFailureDrift(baseline);

        double weighted = (costDrift    * COST_WEIGHT)
                + (latencyDrift * LATENCY_WEIGHT)
                + (qualityDrift * QUALITY_WEIGHT)
                + (failureDrift * FAILURE_WEIGHT);

        LOG.debugf("Drift components — cost=%.3f latency=%.3f quality=%.3f failure=%.3f → total=%.3f",
                costDrift, latencyDrift, qualityDrift, failureDrift, weighted);

        return clamp(weighted);
    }

    // Cost z-score vs baseline
    private double computeCostDrift(ExecutionRecord current, List<DriftRow> baseline) {
        if (current.getCost() == null) return 0.0;
        double currentCost = current.getCost().doubleValue();

        double[] costs = baseline.stream()
                .filter(r -> r.costUsd() != null)
                .mapToDouble(r -> r.costUsd().doubleValue())
                .toArray();
        if (costs.length < 2) return 0.0;

        double mean = mean(costs);
        double std  = std(costs, mean);
        if (std == 0) return 0.0;
        return clamp(Math.abs(currentCost - mean) / std / 3.0);
    }

    // Latency z-score vs baseline
    private double computeLatencyDrift(ExecutionRecord current, List<DriftRow> baseline) {
        double currentLatency = current.getLatencyMs();

        double[] latencies = baseline.stream()
                .mapToDouble(DriftRow::latencyMs)
                .toArray();
        if (latencies.length < 2) return 0.0;

        double mean = mean(latencies);
        double std  = std(latencies, mean);
        if (std == 0) return 0.0;
        return clamp(Math.abs(currentLatency - mean) / std / 3.0);
    }

    // Quality drop below baseline mean
    private double computeQualityDrift(ExecutionRecord current, List<DriftRow> baseline) {
        // current quality comes from OutputQualityScorerService — stored on ExecutionRecord
        // if not yet scored, skip
        if (current.getQualityScore() == null) return 0.0;
        double currentQuality = current.getQualityScore().doubleValue();

        double[] qualities = baseline.stream()
                .filter(r -> r.qualityScore() != null)
                .mapToDouble(r -> r.qualityScore().doubleValue())
                .toArray();
        if (qualities.length < 2) return 0.0;

        double mean = mean(qualities);
        double delta = mean - currentQuality;    // positive = quality dropped
        if (delta <= 0) return 0.0;
        return clamp(delta / mean);
    }

    // Recent 7-day failure rate vs 30-day baseline
    private double computeFailureDrift(List<DriftRow> baseline) {
        long total   = baseline.size();
        long failed  = baseline.stream().filter(DriftRow::isFailed).count();
        double baselineRate = total > 0 ? (double) failed / total : 0.0;

        Instant recentCutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        List<DriftRow> recent = baseline.stream()
                .filter(r -> r.timestamp().isAfter(recentCutoff))
                .toList();

        if (recent.isEmpty()) return 0.0;

        long recentFailed = recent.stream().filter(DriftRow::isFailed).count();
        double recentRate  = (double) recentFailed / recent.size();

        return clamp(Math.max(0, recentRate - baselineRate) * 2.0);
    }

    // ── Math utilities ────────────────────────────────────────────────────────

    private static double mean(double[] v) {
        double s = 0; for (double x : v) s += x; return s / v.length;
    }

    private static double std(double[] v, double mean) {
        double var = 0; for (double x : v) var += (x - mean) * (x - mean);
        return Math.sqrt(var / v.length);
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}


