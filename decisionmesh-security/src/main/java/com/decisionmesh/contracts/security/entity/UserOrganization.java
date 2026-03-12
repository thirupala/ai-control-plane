package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(
        name = "user_organizations",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"user_id", "organization_id"}
        )
)
public class UserOrganization extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public Long id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(name = "organization_id", nullable = false)
    public UUID organizationId;

    @Column(name = "role", nullable = false)
    public String role;

    @Column(name = "joined_at", nullable = false)
    public Instant joinedAt;

    @Column(name = "active", nullable = false)
    public boolean active = true;

    // ===============================
    // FACTORY HELPERS
    // ===============================

    public static UserOrganization createOwner(
            UUID userId,
            UUID organizationId
    ) {
        UserOrganization uo = new UserOrganization();
        uo.userId = userId;
        uo.organizationId = organizationId;
        uo.role = "OWNER";
        uo.joinedAt = Instant.now();
        uo.active = true;
        uo.persist();
        return uo;
    }
}
