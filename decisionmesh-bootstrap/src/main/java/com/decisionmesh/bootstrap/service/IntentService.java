package com.decisionmesh.bootstrap.service;

import com.decisionmesh.bootstrap.dto.IntentEventDto;
import com.decisionmesh.bootstrap.dto.IntentResponse;
import com.decisionmesh.persistence.entity.IntentEntity;
import com.decisionmesh.persistence.entity.IntentEventEntity;
import com.decisionmesh.persistence.repository.IntentRepository;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class IntentService {

    @Inject
    IntentRepository intentRepository;

    // ─────────────────────────────────────────────────────────────
    // Get single intent
    // ─────────────────────────────────────────────────────────────

    public Uni<IntentEntity> getIntent(UUID tenantId, UUID intentId) {
        return intentRepository.findByIdAndTenant(intentId, tenantId);
    }

    // ─────────────────────────────────────────────────────────────
    // Get intent events — serves GET /api/intents/{id}/events
    //
    // Restored after being dropped when the service layer was introduced.
    // ExecutionTimeline.jsx polls this endpoint every 5 seconds and maps
    // each IntentEventDto.eventType to a phase badge on the timeline.
    // Without this method the endpoint returned 404 and the UI showed
    // the intent as permanently stuck at CREATED.
    // ─────────────────────────────────────────────────────────────

    public Uni<List<IntentEventDto>> getIntentEvents(UUID tenantId, UUID intentId) {
        return IntentEventEntity.findByTenantAndIntent(tenantId, intentId)
                .map(events -> events.stream()
                        .map(IntentEventDto::from)
                        .toList());
    }

    // ─────────────────────────────────────────────────────────────
    // Paginated list
    // ─────────────────────────────────────────────────────────────

    public Uni<IntentResponse> getIntents(
            UUID tenantId,
            String phase,
            String sortField,
            String sortDir,
            int pageIndex,
            int pageSize) {

        Uni<List<IntentEntity>> dataUni =
                intentRepository.findPageByTenant(
                        tenantId, phase, sortField, sortDir, pageIndex, pageSize);

        Uni<Long> countUni =
                (phase != null && !phase.isBlank())
                        ? intentRepository.countByTenantAndPhase(tenantId, phase)
                        : intentRepository.countByTenant(tenantId);

        return Uni.combine().all().unis(dataUni, countUni)
                .asTuple()
                .map(tuple -> new IntentResponse(
                        tuple.getItem1(),
                        tuple.getItem2(),
                        pageIndex,
                        pageSize
                ));
    }

    // ─────────────────────────────────────────────────────────────
    // Delete
    // ─────────────────────────────────────────────────────────────

    public Uni<Boolean> deleteIntent(UUID tenantId, UUID intentId) {
        // delete() requires an active transaction — wrap the entire operation
        return Panache.withTransaction(() ->
                intentRepository.findByIdAndTenant(intentId, tenantId)
                        .onItem().ifNull().failWith(() ->
                                new RuntimeException("Intent not found"))
                        .flatMap(entity -> entity.delete())
                        .replaceWith(true)
        );
    }
}