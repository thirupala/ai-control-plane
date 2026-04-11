package com.decisionmesh.llm.persistence;

import com.decisionmesh.application.port.ExecutionRecordQueryPort;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes execution results to execution_records and spend_records.
 * Also provides read queries used by DriftEvaluatorService,
 * ExecutionResource, and DriftResource.
 */
@ApplicationScoped
public class ExecutionRecordRepository implements ExecutionRecordQueryPort {

    // =========================================================================
    // Write queries
    // =========================================================================

    private static final String INSERT_EXECUTION = """
            INSERT INTO execution_records (
                id, intent_id, plan_id, plan_step_id, tenant_id, adapter_id,
                status,
                latency_ms, prompt_tokens, completion_tokens, total_tokens,
                cost_usd, risk_score,
                failure_reason,
                response_text,
                metadata,
                executed_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                ?,
                ?, ?, ?, ?,
                ?, ?,
                ?,
                ?,
                '{}'::jsonb,
                now()
            )
            """;

    private static final String INSERT_SPEND = """
            INSERT INTO spend_records (
                id, intent_id, execution_id, tenant_id, adapter_id,
                amount_usd, token_count,
                budget_ceiling_usd,
                recorded_at
            ) VALUES (
                gen_random_uuid(), ?, ?, ?, ?,
                ?, 0,
                ?,
                now()
            )
            """;

    // =========================================================================
    // Read queries — DriftEvaluatorService
    // =========================================================================

    private static final String SELECT_RECENT_BY_ADAPTER = """
            SELECT
                id,
                status,
                cost_usd,
                latency_ms,
                quality_score,
                executed_at
            FROM execution_records
            WHERE adapter_id  = ?
              AND executed_at >= ?
            ORDER BY executed_at DESC
            LIMIT 500
            """;

    // =========================================================================
    // Read queries — ExecutionResource (UI list)
    // =========================================================================

    private static final String SELECT_LIST_BY_TENANT = """
            SELECT
                er.id,
                er.intent_id,
                er.adapter_id,
                a.name            AS adapter_name,
                er.status,
                er.cost_usd,
                er.latency_ms,
                er.prompt_tokens,
                er.completion_tokens,
                er.total_tokens,
                er.risk_score,
                er.quality_score,
                er.hallucination_detected,
                er.failure_reason,
                er.executed_at
            FROM execution_records er
            LEFT JOIN adapters a ON a.id = er.adapter_id
            WHERE er.tenant_id = ?
            """;

    // =========================================================================
    // Read queries — DriftResource (dashboard summary per adapter)
    // =========================================================================

    private static final String SELECT_DRIFT_SUMMARY = """
            SELECT
                er.adapter_id::TEXT                             AS adapter_id,
                a.name                                          AS adapter_name,
                AVG(er.cost_usd)                                AS avg_cost_usd,
                AVG(er.latency_ms)                              AS avg_latency_ms,
                AVG(er.quality_score)                           AS avg_quality_score,
                AVG(CASE WHEN er.status != 'SUCCESS' THEN 1.0 ELSE 0.0 END) AS failure_rate,
                COUNT(*)                                        AS execution_count,
                MAX(er.executed_at)                             AS last_executed_at
            FROM execution_records er
            LEFT JOIN adapters a ON a.id = er.adapter_id
            WHERE er.tenant_id  = ?
              AND er.executed_at >= ?
            GROUP BY er.adapter_id, a.name
            ORDER BY execution_count DESC
            """;

    // =========================================================================
    // Read queries — DriftResource (daily trend chart)
    // =========================================================================

    private static final String SELECT_DRIFT_TREND = """
            SELECT
                DATE_TRUNC('day', executed_at)   AS day,
                AVG(cost_usd)                    AS avg_cost,
                AVG(latency_ms)                  AS avg_latency,
                COUNT(*)                         AS execution_count
            FROM execution_records
            WHERE tenant_id  = ?
              AND executed_at >= ?
            GROUP BY DATE_TRUNC('day', executed_at)
            ORDER BY day ASC
            """;

    @Inject
    DataSource dataSource;

    // =========================================================================
    // Public write API
    // =========================================================================

    public Uni<Void> save(ExecutionRecord record, Intent intent) {
        return Uni.createFrom().item(() -> {
                    persist(record, intent);
                    return null;
                }).replaceWithVoid()
                .onFailure().invoke(ex ->
                        Log.warnf(ex, "Failed to persist execution record: intent=%s", intent.getId()));
    }

    // =========================================================================
    // Public read API — DriftEvaluatorService
    // =========================================================================

    @Override
    public Uni<List<DriftRow>> findRecentByAdapter(String adapterId, Instant since) {
        return Uni.createFrom().item(() -> {
            List<DriftRow> rows = new ArrayList<>();
            UUID adapterUuid = toUuidOrNull(adapterId);
            if (adapterUuid == null) return rows;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SELECT_RECENT_BY_ADAPTER)) {

                ps.setObject(1, adapterUuid);
                ps.setTimestamp(2, Timestamp.from(since));

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new DriftRow(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("status"),
                                rs.getBigDecimal("cost_usd"),
                                rs.getLong("latency_ms"),
                                rs.getBigDecimal("quality_score"),
                                rs.getTimestamp("executed_at").toInstant()
                        ));
                    }
                }
            } catch (Exception ex) {
                Log.warnf(ex, "findRecentByAdapter failed: adapter=%s since=%s", adapterId, since);
            }
            return rows;
        });
    }

    // =========================================================================
    // Public read API — ExecutionResource (UI list)
    // =========================================================================

    @Override
    public Uni<List<ExecutionRow>> listByTenant(UUID tenantId, int limit,
                                                String phase, String adapterId) {
        return Uni.createFrom().item(() -> {
            List<ExecutionRow> rows = new ArrayList<>();

            // Build dynamic WHERE clauses
            StringBuilder sql = new StringBuilder(SELECT_LIST_BY_TENANT);
            if (phase != null && !phase.isBlank())
                sql.append(" AND er.status = ?");
            if (adapterId != null && !adapterId.isBlank())
                sql.append(" AND er.adapter_id = ?::uuid");
            sql.append(" ORDER BY er.executed_at DESC LIMIT ?");

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql.toString())) {

                int idx = 1;
                ps.setObject(idx++, tenantId);
                if (phase != null && !phase.isBlank())
                    ps.setString(idx++, phase);
                if (adapterId != null && !adapterId.isBlank())
                    ps.setString(idx++, adapterId);
                ps.setInt(idx, limit);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new ExecutionRow(
                                UUID.fromString(rs.getString("id")),
                                uuidOrNull(rs.getString("intent_id")),
                                uuidOrNull(rs.getString("adapter_id")),
                                rs.getString("adapter_name"),
                                rs.getString("status"),
                                rs.getBigDecimal("cost_usd"),
                                rs.getLong("latency_ms"),
                                rs.getInt("prompt_tokens"),
                                rs.getInt("completion_tokens"),
                                rs.getInt("total_tokens"),
                                rs.getBigDecimal("risk_score"),
                                rs.getBigDecimal("quality_score"),
                                rs.getBoolean("hallucination_detected"),
                                rs.getString("failure_reason"),
                                rs.getTimestamp("executed_at").toInstant()
                        ));
                    }
                }
            } catch (Exception ex) {
                Log.errorf(ex, "listByTenant failed: tenantId=%s", tenantId);
            }
            return rows;
        });
    }

    // =========================================================================
    // Public read API — DriftResource (adapter summary)
    // =========================================================================

    @Override
    public Uni<List<AdapterDriftSummary>> getDriftSummary(UUID tenantId, int days) {
        return Uni.createFrom().item(() -> {
            List<AdapterDriftSummary> rows = new ArrayList<>();
            Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SELECT_DRIFT_SUMMARY)) {

                ps.setObject(1, tenantId);
                ps.setTimestamp(2, Timestamp.from(since));

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // avg drift score = weighted combination of cost + latency + failure
                        // mirrors DriftEvaluatorService weights at aggregate level
                        double failureRate  = rs.getDouble("failure_rate");
                        double avgCost      = rs.getDouble("avg_cost_usd");
                        double avgLatency   = rs.getDouble("avg_latency_ms");
                        double avgQuality   = rs.getDouble("avg_quality_score");

                        // Simplified drift score for dashboard overview
                        // Full per-execution drift is stored in intent_drift_evaluations
                        double driftScore = Math.min(1.0, failureRate * 0.5
                                + (avgCost > 0.01 ? 0.1 : 0.0)
                                + (avgLatency > 5000 ? 0.2 : 0.0));

                        Timestamp lastTs = rs.getTimestamp("last_executed_at");

                        rows.add(new AdapterDriftSummary(
                                rs.getString("adapter_id"),
                                rs.getString("adapter_name"),
                                Math.round(driftScore * 10000.0) / 10000.0,
                                avgCost,
                                avgLatency,
                                avgQuality,
                                failureRate,
                                rs.getLong("execution_count"),
                                lastTs != null ? lastTs.toInstant() : null
                        ));
                    }
                }
            } catch (Exception ex) {
                Log.errorf(ex, "getDriftSummary failed: tenantId=%s days=%d", tenantId, days);
            }
            return rows;
        });
    }

    // =========================================================================
    // Public read API — DriftResource (daily trend)
    // =========================================================================

    @Override
    public Uni<List<DriftTrendPoint>> getDriftTrend(UUID tenantId, int days) {
        return Uni.createFrom().item(() -> {
            List<DriftTrendPoint> rows = new ArrayList<>();
            Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SELECT_DRIFT_TREND)) {

                ps.setObject(1, tenantId);
                ps.setTimestamp(2, Timestamp.from(since));

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp day = rs.getTimestamp("day");
                        rows.add(new DriftTrendPoint(
                                day != null ? day.toInstant() : Instant.now(),
                                0.0,                              // avgDrift — from intent_drift_evaluations (extend later)
                                rs.getDouble("avg_cost"),
                                rs.getDouble("avg_latency"),
                                rs.getLong("execution_count")
                        ));
                    }
                }
            } catch (Exception ex) {
                Log.errorf(ex, "getDriftTrend failed: tenantId=%s days=%d", tenantId, days);
            }
            return rows;
        });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void persist(ExecutionRecord record, Intent intent) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute("BEGIN");
            setRls(conn, intent.getTenantId());

            UUID executionId = record.getExecutionId() != null
                    ? record.getExecutionId()
                    : UUID.randomUUID();

            String status    = record.isSuccess() ? "SUCCESS"
                    : (record.getFailureReason() != null ? record.getFailureReason() : "ADAPTER_ERROR");
            double cost      = record.getCost().doubleValue();
            String adapterId = record.getAdapterId();
            UUID adapterUuid = toUuidOrNull(adapterId);

            try (PreparedStatement ps = conn.prepareStatement(INSERT_EXECUTION)) {
                ps.setObject(1,  executionId);
                ps.setObject(2,  record.getIntentId());
                ps.setObject(3,  null);
                ps.setObject(4,  null);
                ps.setObject(5,  intent.getTenantId());
                ps.setObject(6,  adapterUuid);
                ps.setString(7,  status);
                ps.setLong(8,    record.getLatencyMs());
                ps.setInt(9,     0);
                ps.setInt(10,    0);
                ps.setInt(11,    0);
                ps.setDouble(12, cost);
                ps.setDouble(13, 0.0);
                ps.setString(14, record.getFailureReason());
                ps.setString(15, record.getResponseText());
                ps.execute();
            }

            if (record.isSuccess() && cost > 0.0) {
                double ceiling = getBudgetCeiling(intent);
                try (PreparedStatement ps = conn.prepareStatement(INSERT_SPEND)) {
                    ps.setObject(1, record.getIntentId());
                    ps.setObject(2, executionId);
                    ps.setObject(3, intent.getTenantId());
                    ps.setObject(4, adapterUuid);
                    ps.setDouble(5, cost);
                    if (ceiling > 0.0) ps.setDouble(6, ceiling);
                    else ps.setNull(6, java.sql.Types.NUMERIC);
                    ps.execute();
                }
            }

            conn.commit();
        } catch (Exception ex) {
            Log.errorf(ex, "DB error persisting execution record: intent=%s", intent.getId());
        }
    }

    private void setRls(Connection conn, UUID tenantId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SET LOCAL app.current_tenant_id = ?")) {
            ps.setObject(1, tenantId.toString());
            ps.execute();
        }
    }

    private double getBudgetCeiling(Intent intent) {
        if (intent.getBudget() == null) return 0.0;
        return intent.getBudget().getCeilingUsd();
    }

    private UUID toUuidOrNull(String id) {
        if (id == null || id.isBlank()) return null;
        try { return UUID.fromString(id); } catch (Exception e) { return null; }
    }

    private UUID uuidOrNull(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}