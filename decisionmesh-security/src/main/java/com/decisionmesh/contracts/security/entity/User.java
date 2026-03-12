package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "external_user_id"),
                @UniqueConstraint(columnNames = "email")
        }
)
public class User extends PanacheEntityBase {

    @Id
    @Column(name = "user_id")
    public UUID userId;

    /**
     * OIDC subject ("sub" claim).
     * Globally unique per identity provider.
     * This is the canonical identity anchor.
     */
    @Column(name = "external_user_id", nullable = false, unique = true, updatable = false)
    public String externalUserId;

    @Column(nullable = false, unique = true)
    public String email;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "active", nullable = false)
    public boolean active = true;

    // ---------- Static helpers ----------

    public static User findByEmail(String email) {
        return find("email", email).firstResult();
    }

    public static User findByExternalUserId(String externalUserId) {
        return find("externalUserId", externalUserId).firstResult();
    }
}
