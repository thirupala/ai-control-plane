package com.decisionmesh.application.port;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.plan.Plan;
import io.smallrye.mutiny.Uni;

public interface ExecutionEngine {
    Uni<ExecutionRecord> execute(Plan plan, int attempt);
}