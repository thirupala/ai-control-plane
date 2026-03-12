package com.decisionmesh.streaming.projection;

import com.decisionmesh.streaming.worker.ProjectionWorker;

public class IntentProjectionWorker implements ProjectionWorker {

    @Override
    public void handle(String eventType, String payloadJson) {
        // Update IntentView read model
    }
}