package com.decisionmesh.governance.snapshot;

public class BudgetSnapshot {

    private final double remainingBudget;
    private final double spentSoFar;
    private final String currency;

    public BudgetSnapshot(double remainingBudget, double spentSoFar, String currency) {
        this.remainingBudget = remainingBudget;
        this.spentSoFar = spentSoFar;
        this.currency = currency;
    }
}