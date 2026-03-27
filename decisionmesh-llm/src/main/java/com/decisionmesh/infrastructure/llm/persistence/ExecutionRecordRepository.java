package com.decisionmesh.infrastructure.llm.persistence;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Writes execution results to execution_records and spend_records.
 *
 * Uses ONLY methods that exist on the actual ExecutionRecord domain class:
 *   getExecutionId() / getId()  — UUID
 *   getIntentId()               — UUID
 *   getAdapterId()              — String
 *   getAttemptNumber()          — int   (set by DB trigger fn_auto_attempt_number)
 *   getLatencyMs()              — long
 *   getCost()                   — double
 *   getCostUsd()                — BigDecimal
 *   isSuccess()                 — boolean (failureType == null)
 *   getFailureReason()          — String (failureType.name() or null)
 *
 * Fields NOT on the actual ExecutionRecord (skipped with safe defaults):
 *   planId, planStepId, status, promptTokens, completionTokens,
 *   totalTokens, riskScore, failureDetail, traceId
 */
@ApplicationScoped
public class ExecutionRecordRepository {

    // attempt_number omitted — auto-set by fn_auto_attempt_number() trigger
    private static final String INSERT_EXECUTION = """
            INSERT INTO execution_records (
                id, intent_id, plan_id, plan_step_id, tenant_id, adapter_id,
                status,
                latency_ms, prompt_tokens, completion_tokens, total_tokens,
                cost_usd, risk_score,
                failure_reason,
                metadata,
                executed_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                ?,
                ?, ?, ?, ?,
                ?, ?,
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

    @Inject
    DataSource dataSource;

    public Uni<Void> save(ExecutionRecord record, Intent intent) {
        return Uni.createFrom().item(() -> {
            persist(record, intent);
            return null;
        }).replaceWithVoid()
        .onFailure().invoke(ex ->
                Log.warnf(ex, "Failed to persist execution record: intent=%s", intent.getId()));
    }

    private void persist(ExecutionRecord record, Intent intent) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            setRls(conn, intent.getTenantId());

            UUID executionId = record.getExecutionId();

            // Derive status string from isSuccess() — the only method available
            String status        = record.isSuccess() ? "SUCCESS"
                    : (record.getFailureReason() != null ? record.getFailureReason() : "ADAPTER_ERROR");
            double cost          = record.getCost().doubleValue();
            String adapterId     = record.getAdapterId();
            UUID   adapterUuid   = toUuidOrNull(adapterId);

            try (PreparedStatement ps = conn.prepareStatement(INSERT_EXECUTION)) {
                ps.setObject(1,  executionId);
                ps.setObject(2,  record.getIntentId());
                ps.setObject(3,  null);                // planId — not on ExecutionRecord
                ps.setObject(4,  null);                // planStepId — not on ExecutionRecord
                ps.setObject(5,  intent.getTenantId());
                ps.setObject(6,  adapterUuid);
                ps.setString(7,  status);
                ps.setLong(8,    record.getLatencyMs());
                ps.setInt(9,     0);                   // promptTokens — not on ExecutionRecord
                ps.setInt(10,    0);                   // completionTokens — not on ExecutionRecord
                ps.setInt(11,    0);                   // totalTokens — not on ExecutionRecord
                ps.setDouble(12, cost);
                ps.setDouble(13, 0.0);                 // riskScore — not on ExecutionRecord
                ps.setString(14, record.getFailureReason());
                ps.execute();
            }

            // Only insert spend record on success with non-zero cost
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
}
