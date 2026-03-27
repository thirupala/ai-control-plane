package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_users_external_user_id", columnNames = "external_user_id"),
                @UniqueConstraint(name = "uq_users_email",            columnNames = "email")
        }
)
public class UserEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "user_id", updatable = false, nullable = false)
    public UUID userId;

    @Column(name = "external_user_id", unique = true, updatable = false, length = 255)
    public String externalUserId;

    @Column(name = "email", unique = true, length = 255)
    public String email;

    @Column(name = "name", length = 255)
    public String name;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    // ── Reactive finders ──────────────────────────────────────────────────────

    /**
     * Finds a user by email address.
     * Must be called inside a Panache session context:
     *   Panache.withSession(() -> UserEntity.findByEmail(email))
     */
    public static Uni<UserEntity> findByEmail(String email) {
        return find("email", email).firstResult();
    }

    /**
     * Finds a user by their external IdP user ID (e.g. Keycloak sub claim).
     * Must be called inside a Panache session context.
     */
    public static Uni<UserEntity> findByExternalUserId(String externalUserId) {
        return find("externalUserId", externalUserId).firstResult();
    }
}