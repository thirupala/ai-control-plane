package com.decisionmesh.application.port;

import io.smallrye.mutiny.Uni;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port for querying historical execution data.
 *
 * Lives in:    decisionmesh-application (application layer)
 * Implemented: decisionmesh-infrastructure (ExecutionRecordRepository)
 */
public interface ExecutionRecordQueryPort {

    // =========================================================================
    // Drift evaluation — used by DriftEvaluatorService
    // =========================================================================

    /**
     * Returns lightweight execution summaries for a given adapter
     * within the specified time window, ordered by executed_at DESC.
     * Max 500 records — sufficient for statistical baseline.
     */
    Uni<List<DriftRow>> findRecentByAdapter(String adapterId, Instant since);

    // =========================================================================
    // UI queries — used by ExecutionResource and DriftResource
    // =========================================================================

    /**
     * Lists execution records for a tenant, ordered by executed_at DESC.
     * Used by GET /api/executions in ExecutionResource.
     *
     * @param tenantId  tenant scope
     * @param limit     max records to return (default 50)
     * @param phase     optional filter by phase (null = all)
     * @param adapterId optional filter by adapter (null = all)
     */
    Uni<List<ExecutionRow>> listByTenant(UUID tenantId, int limit,
                                         String phase, String adapterId);

    /**
     * Returns drift summary per adapter for a tenant.
     * Used by GET /api/analytics/drift in DriftResource.
     *
     * @param tenantId  tenant scope
     * @param days      look-back window in days (default 30)
     */
    Uni<List<AdapterDriftSummary>> getDriftSummary(UUID tenantId, int days);

    /**
     * Returns overall drift trend (daily averages) for a tenant.
     * Used by the drift dashboard chart.
     *
     * @param tenantId tenant scope
     * @param days     look-back window in days
     */
    Uni<List<DriftTrendPoint>> getDriftTrend(UUID tenantId, int days);

    // =========================================================================
    // Records (data carriers — not domain objects)
    // =========================================================================

    /**
     * Full execution row for the UI — all columns the ExecutionMonitor needs.
     */
    record ExecutionRow(
            UUID       id,
            UUID       intentId,
            UUID       adapterId,
            String     adapterName,       // joined from adapters table
            String     status,
            BigDecimal costUsd,
            long       latencyMs,
            int        promptTokens,
            int        completionTokens,
            int        totalTokens,
            BigDecimal riskScore,
            BigDecimal qualityScore,
            boolean    hallucinationDetected,
            String     failureReason,     // nullable
            Instant    executedAt
    ) {}

    /**
     * Drift summary per adapter — for the drift dashboard table.
     */
    record AdapterDriftSummary(
            String     adapterId,
            String     adapterName,       // joined from adapters table
            double     avgDriftScore,
            double     avgCostUsd,
            double     avgLatencyMs,
            double     avgQualityScore,
            double     failureRate,
            long       executionCount,
            Instant    lastExecutedAt
    ) {}

    /**
     * Single point on the drift trend chart (daily aggregate).
     */
    record DriftTrendPoint(
            Instant    date,
            double     avgDrift,
            double     avgCost,
            double     avgLatency,
            long       executionCount
    ) {}

    /**
     * Minimal read projection for drift calculation.
     * Carries only the five columns DriftEvaluatorService needs.
     */
    record DriftRow(
            UUID       id,
            String     status,
            BigDecimal costUsd,
            long       latencyMs,
            BigDecimal qualityScore,   // nullable
            Instant    timestamp
    ) {
        public boolean isFailed() {
            return !"SUCCESS".equalsIgnoreCase(status);
        }
    }
}