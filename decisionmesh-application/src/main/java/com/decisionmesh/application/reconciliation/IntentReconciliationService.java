package com.decisionmesh.application.reconciliation;

import com.decisionmesh.application.port.ExecutionRepositoryPort;
import com.decisionmesh.application.port.IntentRepositoryPort;
import com.decisionmesh.application.port.SLAGuard;
import com.decisionmesh.application.telemetry.TelemetryPublisher;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class IntentReconciliationService implements ReconciliationService {

    @Inject
    IntentRepositoryPort intentRepository;
    @Inject
    ExecutionRepositoryPort executionRepository;
    @Inject
    SLAGuard slaGuard;
    @Inject
    TelemetryPublisher telemetryPublisher;


    public IntentReconciliationService(IntentRepositoryPort intentRepository,
                                       ExecutionRepositoryPort executionRepository,
                                       SLAGuard slaGuard,
                                       TelemetryPublisher telemetryPublisher) {
        this.intentRepository = intentRepository;
        this.executionRepository = executionRepository;
        this.slaGuard = slaGuard;
        this.telemetryPublisher = telemetryPublisher;
    }

    @Override
    public Uni<Void> reconcile(UUID tenantId, UUID intentId) {

        return intentRepository.findById(tenantId, intentId)
                .flatMap((Intent intent) -> reconcileIntent(intent));
    }

    private Uni<Void> reconcileIntent(Intent intent) {

        if (intent.isTerminal()) {
            return Uni.createFrom().voidItem();
        }

        return executionRepository.findByIntentId(intent.getId())
                .flatMap((List<ExecutionRecord> records) -> {

                    if (records == null || records.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }

                    ExecutionRecord latest =
                            records.stream()
                                    .max(Comparator.comparingInt(
                                            ExecutionRecord::getAttemptNumber))
                                    .orElseThrow();

                    return slaGuard.validateAfterExecution(intent, latest)
                            .flatMap(ignored -> {

                                if (latest.isSuccess()) {
                                    intent.markSatisfied();
                                } else if (intent.getRetryCount()
                                        < intent.getConstraints().maxRetries()) {
                                    intent.scheduleRetry();
                                } else {
                                    intent.markViolated();
                                }

                                return intentRepository.save(intent);
                            })
                            .flatMap(ignored ->
                                    telemetryPublisher.publish(
                                            intent.getPhase(),
                                            intent.getTenantId(),
                                            intent.getId(),
                                            intent.getVersion()
                                    )
                            );
                });
    }
}
