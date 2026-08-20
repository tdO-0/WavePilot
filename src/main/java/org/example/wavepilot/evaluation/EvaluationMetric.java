package org.example.wavepilot.evaluation;

/** A metric is always the ratio of passed cases over executed cases for a group of case types. */
public record EvaluationMetric(
        String metricName,
        String description,
        long numerator,
        long denominator,
        double value) {
}
