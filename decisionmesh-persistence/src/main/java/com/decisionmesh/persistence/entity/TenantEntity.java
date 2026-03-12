package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.util.UUID;

@Entity
public class TenantEntity extends PanacheEntityBase {

    public Long getId1() {
        return id1;
    }

    public void setId1(Long id1) {
        this.id1 = id1;
    }

    @Id
    private Long id1;
    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, unique = true)
    public String name;

    public String createdBy;
}
