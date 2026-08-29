package org.example.wavepilot.scientific.model;

public enum GoalOperator {
    GREATER_THAN_OR_EQUAL {
        @Override public boolean test(double actual, double target) { return actual >= target; }
    },
    LESS_THAN_OR_EQUAL {
        @Override public boolean test(double actual, double target) { return actual <= target; }
    };

    public abstract boolean test(double actual, double target);
}
