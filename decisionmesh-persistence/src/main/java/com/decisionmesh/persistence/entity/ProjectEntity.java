package com.decisionmesh.persistence.entity;


import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects",
        indexes = @Index(name = "idx_projects_tenant", columnList = "tenant_id"))
public class ProjectEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "description")
    public String description;

    @Column(name = "environment", nullable = false, length = 50)
    public String environment;    // Production | Staging | Dev

    @Column(name = "is_default", nullable = false)
    public boolean isDefault;

    @Column(name = "created_at")
    public Instant createdAt;
}