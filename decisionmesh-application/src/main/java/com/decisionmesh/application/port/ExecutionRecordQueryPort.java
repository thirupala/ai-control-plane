package com.decisionmesh.application.port;


import io.smallrye.mutiny.Uni;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port for querying historical execution data needed by DriftEvaluatorService.
 *
 * Lives in:    decisionmesh-application (application layer)
 * Implemented: decisionmesh-infrastructure (ExecutionRecordRepository)
 *
 * Follows the same pattern as IntentRepositoryPort, ExecutionRepositoryPort etc.
 * that the orchestrator already uses — application defines the port,
 * infrastructure provides the adapter.
 *
 * DriftEvaluatorService injects this port — it never sees the concrete
 * ExecutionRecordRepository class.
 */
public interface ExecutionRecordQueryPort {

    /**
     * Returns lightweight execution summaries for a given adapter
     * within the specified time window, ordered by executed_at DESC.
     *
     * Used by DriftEvaluatorService to build the baseline for drift calculation.
     * Max 500 records — sufficient for statistical baseline.
     */
    Uni<List<DriftRow>> findRecentByAdapter(String adapterId, Instant since);

    /**
     * Minimal read projection — cost, latency, quality, status, timestamp.
     * Carries only the five columns drift calculation needs.
     * Not a domain object — a data carrier that lives in the port layer.
     */
    record DriftRow(
            UUID       id,
            String     status,
            BigDecimal costUsd,
            long       latencyMs,
            BigDecimal qualityScore,   // nullable — V5 migration column
            Instant    timestamp
    ) {
        public boolean isFailed() {
            return !"SUCCESS".equalsIgnoreCase(status);
        }
    }
}
