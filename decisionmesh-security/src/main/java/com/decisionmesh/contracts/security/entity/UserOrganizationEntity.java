package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "user_organizations",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"user_id", "organization_id"})
        }
)
public class UserOrganizationEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(name = "organization_id", nullable = false)
    public UUID organizationId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "role", nullable = false)
    public String role;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions", nullable = false, columnDefinition = "jsonb")
    public String permissions = "[]";               // stored as raw JSON string

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}