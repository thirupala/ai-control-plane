package com.decisionmesh.contracts.security.entity;


import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_idempotency",
        uniqueConstraints = @UniqueConstraint(columnNames = "idempotencyKey"))
public class TenantIdempotency extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(nullable = false, unique = true)
    public String idempotencyKey;

    @Column(nullable = false)
    public UUID tenantId;

    @Column(nullable = false)
    public Instant createdAt;
}
