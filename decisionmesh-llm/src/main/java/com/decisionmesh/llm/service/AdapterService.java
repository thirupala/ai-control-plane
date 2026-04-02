package com.decisionmesh.llm.service;

import com.decisionmesh.persistence.entity.AdapterEntity;
import com.decisionmesh.persistence.repository.AdapterRepository;
import io.smallrye.mutiny.Uni;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

import java.util.*;

@ApplicationScoped
public class AdapterService {

    @Inject
    AdapterRepository repository;

    // ─────────────────────────────────────────────
    // LIST
    // ─────────────────────────────────────────────
    public Uni<List<AdapterEntity>> list(UUID tenantId) {
        return repository.findByTenant(tenantId);
    }

    // ─────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────
    @WithTransaction
    public Uni<AdapterEntity> create(UUID tenantId, AdapterEntity req) {

        validate(req);

        AdapterEntity e = new AdapterEntity();
        e.tenantId = tenantId;
        e.name = req.name;
        e.provider = normalizeProvider(req.provider);
        e.adapterType = req.adapterType != null ? req.adapterType : "LLM";
        e.modelId = req.modelId;
        e.region = req.region;

        e.config = req.config != null ? req.config : new HashMap<>();
        e.capabilityFlags = req.capabilityFlags != null ? req.capabilityFlags : new HashMap<>();
        e.allowedIntentTypes = req.allowedIntentTypes != null ? req.allowedIntentTypes : new ArrayList<>();

        e.isActive = req.isActive;

        return repository.persist(e);
    }

    // ─────────────────────────────────────────────
    // UPDATE
    // ─────────────────────────────────────────────
    @WithTransaction
    public Uni<AdapterEntity> update(UUID tenantId, UUID id, AdapterEntity req) {

        validate(req);

        return repository.findById(tenantId, id)
                .onItem().ifNull().failWith(() -> new RuntimeException("Adapter not found"))
                .flatMap(e -> {
                    e.name = req.name;
                    e.modelId = req.modelId;
                    e.provider = normalizeProvider(req.provider);
                    e.adapterType = req.adapterType;
                    e.region = req.region;
                    e.config = req.config;
                    e.capabilityFlags = req.capabilityFlags;
                    e.allowedIntentTypes = req.allowedIntentTypes;

                    return repository.persist(e);
                });
    }

    // ─────────────────────────────────────────────
    // TOGGLE
    // ─────────────────────────────────────────────
    @WithTransaction
    public Uni<AdapterEntity> toggle(UUID tenantId, UUID id, boolean active) {
        return repository.findById(tenantId, id)
                .onItem().ifNull().failWith(() -> new RuntimeException("Adapter not found"))
                .flatMap(e -> {
                    e.isActive = active;
                    return repository.persist(e);
                });
    }

    // ─────────────────────────────────────────────
    // DELETE
    // ─────────────────────────────────────────────
    @WithTransaction
    public Uni<Void> delete(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .onItem().ifNull().failWith(() -> new RuntimeException("Adapter not found"))
                .flatMap(repository::delete);
    }

    // ─────────────────────────────────────────────
    // VALIDATION
    // ─────────────────────────────────────────────
    private void validate(AdapterEntity req) {
        if (req.name == null || req.name.isBlank()) {
            throw new BadRequestException("Adapter name is required");
        }
    }

    // ─────────────────────────────────────────────
    // NORMALIZATION
    // ─────────────────────────────────────────────
    private String normalizeProvider(String provider) {
        if (provider == null) return "CUSTOM";

        String val = provider.toUpperCase();

        if (val.equals("GOOGLE")) return "GEMINI";
        if (val.equals("AZURE")) return "AZURE_OPENAI";

        return val;
    }
}