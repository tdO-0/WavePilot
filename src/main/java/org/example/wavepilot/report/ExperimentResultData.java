package org.example.wavepilot.report;

import java.util.List;
import java.util.Map;

/**
 * Generic, template-agnostic experiment result model. Unlike the legacy polar-oriented
 * {@code ExperimentReportData} (codeLength/trueK/errorRate/accuracy), this model describes
 * results through the template's own dimensions and metrics: each {@link MetricSeries}
 * carries the actual dimension values (e.g. Eb/N0, erasure probability, SNR) and the
 * metric values (e.g. BER, SER, EVM) exactly as the validated CSV defines them.
 */
public record ExperimentResultData(
        String jobId,
        String experimentTypeId,
        String templateId,
        String templateVersion,
        String algorithmName,
        String algorithmVersion,
        String classification,
        boolean mock,
        boolean algorithmValidated,
        Map<String, Object> parameters,
        List<String> dimensions,
        List<String> metrics,
        List<MetricSeries> series,
        Map<String, Double> aggregates,
        String runnerType,
        String matlabVersion,
        List<ArtifactView> artifacts,
        List<ArtifactCitation> citations,
        List<ReportConclusion> conclusions) {

    public ExperimentResultData {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        series = series == null ? List.of() : List.copyOf(series);
        aggregates = aggregates == null ? Map.of() : Map.copyOf(aggregates);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        citations = citations == null ? List.of() : List.copyOf(citations);
        conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
    }

    /** One row of the experiment grid: dimension values + metric values with citations. */
    public record MetricSeries(
            Map<String, Object> dimensionValues,
            Map<String, Double> metricValues,
            Map<String, String> citationIds,
            int rowReference) {

        public MetricSeries {
            dimensionValues = dimensionValues == null ? Map.of() : Map.copyOf(dimensionValues);
            metricValues = metricValues == null ? Map.of() : Map.copyOf(metricValues);
            citationIds = citationIds == null ? Map.of() : Map.copyOf(citationIds);
        }
    }

    public record ArtifactView(String artifactId, String artifactType, String relativePath,
                               String sha256, long size, String mimeType, boolean validated) { }
}
