package org.example.wavepilot.template.definition;

import java.util.List;

/**
 * One declared report metric: which CSV column it is computed from, which aggregation is
 * applied and along which dimensions it may be grouped.
 */
public record MetricDefinition(
        String metricName,
        String displayName,
        String unit,
        String sourceColumn,
        Aggregation aggregation,
        List<String> groupByDimensions) {

    public MetricDefinition {
        groupByDimensions = groupByDimensions == null ? List.of() : List.copyOf(groupByDimensions);
    }

    public enum Aggregation {
        MIN,
        MAX,
        MEAN,
        LATEST
    }
}
