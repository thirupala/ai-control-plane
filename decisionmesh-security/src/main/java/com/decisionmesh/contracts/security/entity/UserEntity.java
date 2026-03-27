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
public class UserEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "user_id", updatable = false, nullable = false)
    public UUID userId;

    @Column(name = "external_user_id", nullable = false, unique = true, updatable = false)
    public String externalUserId;

    @Column(nullable = false, unique = true)
    public String email;

    @Column(name = "name")                          //   added
    public String name;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    // ---------- Static helpers ----------

    public static UserEntity findByEmail(String email) {
        return find("email", email).firstResult();
    }

    public static UserEntity findByExternalUserId(String externalUserId) {
        return find("externalUserId", externalUserId).firstResult();
    }
}