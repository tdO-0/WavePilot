package org.example.wavepilot.report;

import java.util.List;
import java.util.Map;

/**
 * Everything a grounded analysis model may see. All numbers originate from validated
 * artifacts (CSV cells, summary JSON) and carry citations; the model has no filesystem or
 * repository handle and can only reason about the data listed here.
 */
public record GroundedAnalysisContext(
        String experimentTypeId,
        String templateId,
        String templateVersion,
        Map<String, Object> parameters,
        List<String> dimensions,
        List<String> metrics,
        List<ExperimentResultData.MetricSeries> series,
        Map<String, Double> aggregates,
        List<ArtifactCitation> citations,
        boolean mock,
        boolean algorithmValidated,
        String classification) {

    public GroundedAnalysisContext {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        series = series == null ? List.of() : List.copyOf(series);
        aggregates = aggregates == null ? Map.of() : Map.copyOf(aggregates);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public static GroundedAnalysisContext from(ExperimentResultData data) {
        return new GroundedAnalysisContext(data.experimentTypeId(), data.templateId(),
                data.templateVersion(), data.parameters(), data.dimensions(), data.metrics(),
                data.series(), data.aggregates(), data.citations(), data.mock(),
                data.algorithmValidated(), data.classification());
    }
}
