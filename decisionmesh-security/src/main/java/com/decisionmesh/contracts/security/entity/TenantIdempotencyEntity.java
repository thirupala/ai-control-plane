package com.decisionmesh.contracts.security.entity;


import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_idempotency")
public class TenantIdempotencyEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    public UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    public String idempotencyKey;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    public static TenantIdempotencyEntity findByKey(String key) {
        return find("idempotencyKey", key).firstResult();
    }
}
