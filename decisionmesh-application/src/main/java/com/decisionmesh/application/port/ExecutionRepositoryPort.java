package com.decisionmesh.application.port;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.UUID;

public interface ExecutionRepositoryPort {

    Uni<Void> append(ExecutionRecord record);

    Uni<List<ExecutionRecord>> findByIntentId(UUID id);

    Uni<Void> appendAll(List<ExecutionRecord> executions);
}