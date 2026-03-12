package com.decisionmesh.governance.snapshot;

import java.time.Instant;

public class SLASnapshot {

    private final Instant windowStart;
    private final Instant windowEnd;
    private final boolean breached;

    public SLASnapshot(Instant windowStart, Instant windowEnd, boolean breached) {
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.breached = breached;
    }
}