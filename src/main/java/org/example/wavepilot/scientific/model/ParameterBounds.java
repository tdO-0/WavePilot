package org.example.wavepilot.scientific.model;

public record ParameterBounds(double minimum, double maximum, double maximumChangePerReplan) {
    public ParameterBounds {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum) {
            throw new IllegalArgumentException("parameter bounds must be finite and ordered");
        }
        if (!Double.isFinite(maximumChangePerReplan) || maximumChangePerReplan <= 0) {
            throw new IllegalArgumentException("maximumChangePerReplan must be > 0");
        }
    }

    public double clamp(double current, double proposed) {
        double changeBounded = Math.max(current - maximumChangePerReplan,
                Math.min(current + maximumChangePerReplan, proposed));
        return Math.max(minimum, Math.min(maximum, changeBounded));
    }

    public boolean contains(double value) {
        return Double.isFinite(value) && value >= minimum - 1.0e-12 && value <= maximum + 1.0e-12;
    }
}
