package com.decisionmesh.persistence.entity;


import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "org_branding")
public class OrgBrandingEntity extends PanacheEntityBase {

    @Id
    @Column(name = "tenant_id")
    public UUID tenantId;

    @Column(name = "org_name", length = 255)
    public String orgName;

    @Column(name = "primary_color", length = 7)
    public String primaryColor;    // hex e.g. #2563eb

    @Column(name = "logo_url")
    public String logoUrl;

    @Column(name = "favicon")
    public String favicon;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}