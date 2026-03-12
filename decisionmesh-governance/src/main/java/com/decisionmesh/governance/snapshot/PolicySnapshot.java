package com.decisionmesh.governance.snapshot;

public class PolicySnapshot {

    private final String policyVersion;
    private final String decision;
    private final String enforcementMode;
    private final String evaluatedContextJson;

    public PolicySnapshot(String policyVersion,
                          String decision,
                          String enforcementMode,
                          String evaluatedContextJson) {
        this.policyVersion = policyVersion;
        this.decision = decision;
        this.enforcementMode = enforcementMode;
        this.evaluatedContextJson = evaluatedContextJson;
    }
}