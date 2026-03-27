package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "policy_evaluations", indexes = {
        @Index(name = "idx_poleval_intent", columnList = "intent_id"),
        @Index(name = "idx_poleval_tenant", columnList = "tenant_id")
})
public class PolicyEvaluationEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    @Column(name = "policy_id", nullable = false)
    public UUID policyId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "phase", nullable = false, length = 50)
    public String phase;              // PRE_SUBMISSION, PRE_EXECUTION, POST_EXECUTION, CONTINUOUS

    @Column(name = "result", nullable = false, length = 50)
    public String result = "ALLOWED"; // ALLOWED, WARNING, VIOLATION

    @Column(name = "enforcement_mode", nullable = false, length = 50)
    public String enforcementMode = "LOG_ONLY";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> contextSnapshot;

    @Column(name = "block_reason", length = 512)
    public String blockReason;

    @Column(name = "adapter_id")
    public UUID adapterId;

    @Column(name = "attempt_number")
    public Integer attemptNumber;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    public Instant evaluatedAt = Instant.now();

    // ── Finders ───────────────────────────────────────────────────────

    public static List<PolicyEvaluationEntity> findByIntent(UUID intentId) {
        return list("intentId = ?1 ORDER BY evaluatedAt ASC", intentId);
    }

    public static List<PolicyEvaluationEntity> findViolationsByTenant(UUID tenantId) {
        return list("tenantId = ?1 AND result = 'VIOLATION' ORDER BY evaluatedAt DESC", tenantId);
    }
}