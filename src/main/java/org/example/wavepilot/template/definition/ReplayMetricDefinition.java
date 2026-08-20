package org.example.wavepilot.template.definition;

/**
 * One declared replay comparison metric: which CSV column the source and replay runs are
 * compared on, the maximum absolute difference tolerance (and optionally a mean-absolute
 * tolerance), and whether the metric is required for the reproducibility verdict.
 */
public record ReplayMetricDefinition(
        String comparisonColumn,
        Double maxAbsoluteTolerance,
        Double meanAbsoluteTolerance,
        boolean compareMean,
        boolean required) {
}
