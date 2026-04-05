package com.decisionmesh.llm.persistence;

import com.decisionmesh.application.port.ExecutionRecordQueryPort;
import com.decisionmesh.application.port.ExecutionRecordQueryPort.DriftRow;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes execution results to execution_records and spend_records.
 * Also provides read queries used by DriftEvaluatorService and
 * OutputQualityScorerService.
 */
@ApplicationScoped
public class ExecutionRecordRepository implements ExecutionRecordQueryPort {

    // ── Write queries ─────────────────────────────────────────────────────────

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

    // ── Read queries (used by DriftEvaluatorService) ──────────────────────────

    /**
     * Returns lightweight execution summary rows for a given adapter
     * within the specified time window.
     *
     * Used by DriftEvaluatorService to build the baseline for drift calculation.
     * Returns only the columns needed for drift: status, cost_usd, latency_ms,
     * quality_score, executed_at.
     */
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

    @Inject
    DataSource dataSource;

    // ── Public write API ──────────────────────────────────────────────────────

    public Uni<Void> save(ExecutionRecord record, Intent intent) {
        return Uni.createFrom().item(() -> {
                    persist(record, intent);
                    return null;
                }).replaceWithVoid()
                .onFailure().invoke(ex ->
                        Log.warnf(ex, "Failed to persist execution record: intent=%s", intent.getId()));
    }

    // ── Public read API ───────────────────────────────────────────────────────

    /**
     * Fetch recent execution summaries for an adapter, back to {@code since}.
     * Returns a list of {@link DriftRow} — a minimal projection used only
     * by DriftEvaluatorService. No domain object hydration needed.
     */
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
                                rs.getBigDecimal("quality_score"),   // may be null — handled by drift calc
                                rs.getTimestamp("executed_at").toInstant()
                        ));
                    }
                }

            } catch (Exception ex) {
                Log.warnf(ex, "findRecentByAdapter failed for adapter=%s since=%s", adapterId, since);
            }
            return rows;
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void persist(ExecutionRecord record, Intent intent) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // FIX 1: BEGIN the transaction explicitly so SET LOCAL takes effect.
            // SET LOCAL scopes the setting to the current transaction — it has no
            // effect outside one. With autoCommit=false, a transaction only starts
            // after the first statement; calling SET LOCAL before any DML meant it
            // ran in autocommit mode and was immediately reset.
            conn.createStatement().execute("BEGIN");
            setRls(conn, intent.getTenantId());

            // FIX 2: getExecutionId() returns null because ExecutionRecord.of() in
            // the LLM adapters does not supply one. ps.setObject(1, null) sends a
            // literal NULL to the PK column — PostgreSQL's DEFAULT gen_random_uuid()
            // only fires when the column is OMITTED, not when NULL is passed explicitly.
            // This threw a NOT NULL constraint violation, caught and swallowed silently,
            // leaving execution_records permanently empty → cost analytics showed $0.
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
                ps.setString(15, record.getResponseText());   // null-safe — TEXT column accepts null
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
        try (PreparedStatement ps = conn.prepareStatement("SET LOCAL app.current_tenant_id = ?")) {
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

    // DriftRow is defined in ExecutionRecordQueryPort — this class implements that port.
    // No local DriftRow record needed.
}