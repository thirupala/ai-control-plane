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
@Table(name = "policies", indexes = {
        @Index(name = "idx_policies_tenant", columnList = "tenant_id"),
        @Index(name = "idx_policies_phase",  columnList = "tenant_id, phase, is_active")
})
public class PolicyEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    @Column(name = "scope", nullable = false, length = 50)
    public String scope = "TENANT";         // TENANT, ORGANIZATION, INTENT_TYPE, ADAPTER

    @Column(name = "scope_ref_id")
    public UUID scopeRefId;

    @Column(name = "phase", nullable = false, length = 50)
    public String phase = "PRE_EXECUTION";  // PRE_SUBMISSION, PRE_EXECUTION, POST_EXECUTION, CONTINUOUS

    @Column(name = "enforcement_mode", nullable = false, length = 50)
    public String enforcementMode = "LOG_ONLY"; // HARD_STOP, WARN_ONLY, LOG_ONLY

    @Column(name = "policy_type", nullable = false, length = 100)
    public String policyType = "CUSTOM_DSL";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_dsl", nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> ruleDsl;

    @Column(name = "priority", nullable = false)
    public int priority = 100;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    @Version
    @Column(name = "version", nullable = false)
    public int version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Finders ───────────────────────────────────────────────────────

    public static List<PolicyEntity> findActiveByTenantAndPhase(UUID tenantId, String phase) {
        return list("tenantId = ?1 AND phase = ?2 AND isActive = true ORDER BY priority ASC",
                tenantId, phase);
    }

    public static List<PolicyEntity> findActiveByTenant(UUID tenantId) {
        return list("tenantId = ?1 AND isActive = true ORDER BY priority ASC", tenantId);
    }
}