package com.decisionmesh.domain.value;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class Budget {

    private final double allocated;
    private final double consumed;

    private Budget(double allocated, double consumed) {
        if (allocated < 0) throw new IllegalArgumentException("Allocated must be >= 0");
        if (consumed < 0) throw new IllegalArgumentException("Consumed must be >= 0");
        if (consumed > allocated) throw new IllegalArgumentException("Consumed exceeds allocation");

        this.allocated = allocated;
        this.consumed = consumed;
    }

    /**
     * Jackson deserialization — accepts {"amount": 10.0} or {"allocated": 10.0} from REST body.
     * "consumed" defaults to 0 since clients never send it.
     */
    @JsonCreator
    public static Budget fromRequest(
            @JsonProperty("amount")    Double amount,
            @JsonProperty("allocated") Double allocated) {

        double value = amount != null ? amount : allocated != null ? allocated : 0.0;
        return new Budget(value, 0.0);
    }

    public static Budget of(double allocated) {
        return new Budget(allocated, 0.0);
    }

    public Budget consume(double amount) {
        return new Budget(this.allocated, this.consumed + amount);
    }

    public boolean isExceeded() {
        return consumed > allocated;
    }

    public double remaining() {
        return allocated - consumed;
    }

    public double allocated() {
        return allocated;
    }

    public double consumed() {
        return consumed;
    }
}