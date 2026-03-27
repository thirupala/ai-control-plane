package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class TenantEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "external_id", nullable = false, unique = true)
    public String externalId;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "organization_id", nullable = false)
    public UUID organizationId;

    @Column(name = "status", nullable = false)
    public String status = "ACTIVE";

    @JdbcTypeCode(SqlTypes.JSON)                                    // ✅ fix jsonb cast
    @Column(name = "config", columnDefinition = "jsonb")
    public String config = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public static TenantEntity findByExternalId(String externalId) {
        return find("externalId", externalId).firstResult();
    }

    public static TenantEntity findByName(String name) {
        return find("name", name).firstResult();
    }
}