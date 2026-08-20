package org.example.wavepilot.replay;

import org.example.wavepilot.experiment.model.ExperimentType;

import java.util.List;

/**
 * Type-specific set of numeric CSV columns a replay comparison must compare. Code-level
 * registration: each experiment type declares which structured metrics define its
 * reproducibility, and ReplayComparisonEvaluator dispatches on the job's experiment type.
 */
public interface ExperimentComparisonMetrics {

    ExperimentType experimentType();

    /** The declarative experimentTypeId this strategy serves, or null for built-in types. */
    default String experimentTypeId() {
        return null;
    }

    /**
     * CSV columns that define the parameter grid. An empty list means every CSV row is its
     * own grid point (row-aligned comparison); declarative templates currently use this
     * because sweep parameter names do not reliably map to CSV column names.
     */
    default List<String> gridColumns() {
        return List.of("codeLength", "errorRate");
    }

    List<Metric> metrics();

    record Metric(String name, boolean meanAlso) { }
}
