package com.decisionmesh.domain.value;

public final class DriftScore {

    private final double value;

    private DriftScore(double value) {
        if (value < 0.0)
            throw new IllegalArgumentException("Drift score must be >= 0");

        this.value = value;
    }

    public static DriftScore of(double value) {
        return new DriftScore(value);
    }

    public double value() {
        return value;
    }
}
