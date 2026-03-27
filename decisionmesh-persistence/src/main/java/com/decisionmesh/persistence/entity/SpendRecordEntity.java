package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "spend_records", indexes = {
        @Index(name = "idx_spend_intent", columnList = "intent_id"),
        @Index(name = "idx_spend_tenant", columnList = "tenant_id")
})
public class SpendRecordEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    @Column(name = "execution_id", nullable = false)
    public UUID executionId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "adapter_id")
    public UUID adapterId;

    @Column(name = "amount_usd", nullable = false, precision = 12, scale = 6)
    public BigDecimal amountUsd = BigDecimal.ZERO;

    @Column(name = "token_count")
    public Integer tokenCount = 0;

    @Column(name = "budget_ceiling_usd", precision = 12, scale = 6)
    public BigDecimal budgetCeilingUsd;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    public Instant recordedAt = Instant.now();

    // ── Finders ───────────────────────────────────────────────────────

    public static List<SpendRecordEntity> findByIntent(UUID intentId) {
        return list("intentId = ?1 ORDER BY recordedAt ASC", intentId);
    }

    public static BigDecimal totalSpendByIntent(UUID intentId) {
        return (BigDecimal) getEntityManager()
                .createQuery("SELECT COALESCE(SUM(s.amountUsd), 0) FROM SpendRecordEntity s WHERE s.intentId = :id")
                .setParameter("id", intentId)
                .getSingleResult();
    }
}