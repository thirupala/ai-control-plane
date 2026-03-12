package com.decisionmesh.streaming.projection;

import com.decisionmesh.streaming.worker.ProjectionWorker;

public class ExecutionAnalyticsProjectionWorker implements ProjectionWorker {

    @Override
    public void handle(String eventType, String payloadJson) {
        // Update ExecutionAnalyticsView read model
    }
}