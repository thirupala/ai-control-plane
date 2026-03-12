package com.decisionmesh.contracts.security.entity;


import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant extends PanacheEntityBase {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    public UUID tenantId;

    @Column(name = "organization_name", nullable = false)
    public String organizationName;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "active", nullable = false)
    public boolean active = true;

    // ---------- Static helpers ----------

    public static Tenant findByTenantId(String tenantId) {
        return find("tenantId", tenantId).firstResult();
    }
}
