package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "intents")
public class IntentEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "phase", nullable = false)
    public String phase;

    @Column(name = "satisfaction")
    public String satisfaction;

    @Column(name = "retry_count", nullable = false)
    public int retryCount;

    @Column(name = "max_retries", nullable = false)
    public int maxRetries;

    @Column(name = "drift_score", nullable = false)
    public double driftScore;

    @Version
    @Column(name = "version", nullable = false)
    public long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}