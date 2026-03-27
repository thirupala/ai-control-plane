package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "organizations")
public class OrganizationEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)       //   was "organization_id"
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "description")
    public String description;                                       //   added


    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb")
    public String config = "{}";                                   //   added NOT NULL

    @Column(name = "is_active", nullable = false)                   //   was "active"
    public boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;                                        //   added NOT NULL

    // ---------- Queries ----------
    public static List<OrganizationEntity> findByTenant(UUID tenantId) {
        return list("tenantId", tenantId);                          //   UUID not String
    }
}