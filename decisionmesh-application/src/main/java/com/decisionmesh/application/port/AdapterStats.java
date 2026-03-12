package com.decisionmesh.application.port;

public record AdapterStats(
        double avgCost,
        long avgLatency,
        double failureRate
) {

}
