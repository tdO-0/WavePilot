package org.example.wavepilot.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.definition.MetricDefinition;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative metrics extraction: parses the CSV of a declarative template into the shared
 * report row shape (first grid dimension maps to codeLength, first metric column maps to
 * accuracy) and computes every declared metric aggregation (min/max/mean/latest, optionally
 * grouped by dimensions) into the summary node. The report chain therefore works for
 * declarative templates while the type-specific metric values stay available from summary.
 */
public class DeclarativeMetricsExtractor implements ExperimentMetricsExtractor {

    private final ExperimentDefinitionRegistry definitionRegistry;
    private final ArtifactRegistry registry;
    private final ObjectMapper objectMapper;

    public DeclarativeMetricsExtractor(ExperimentDefinitionRegistry definitionRegistry,
                                       ArtifactRegistry registry, ObjectMapper objectMapper) {
        this.definitionRegistry = definitionRegistry;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExperimentType experimentType() {
        return null;
    }

    @Override
    public ExtractedMetrics extract(ArtifactRegistry extractRegistry, ExperimentJob job,
                                    List<ArtifactRecord> artifacts) {
        String experimentTypeId = job.getGenericSpec() != null
                ? job.getGenericSpec().experimentTypeId()
                : job.getSpec() == null ? null : job.getSpec().experimentTypeId();
        ExperimentDefinition definition = definitionRegistry
                .byExperimentTypeId(experimentTypeId)
                .orElseThrow(() -> new ReportDataAssembler.ReportDataException(
                        "No declarative definition for experimentTypeId: "
                                + experimentTypeId));
        ArtifactRecord csv = require(artifacts, ArtifactType.ACCURACY_CSV, job.getJobId());
        try {
            String firstMetricColumn = definition.metrics().isEmpty()
                    ? null : definition.metrics().get(0).sourceColumn();
            ParsedCsv parsed = readCsv(csv, firstMetricColumn);
            if (parsed.rows().isEmpty()) {
                throw new ReportDataAssembler.ReportDataException("accuracy.csv contains no result rows");
            }
            List<MetricRow> rows = parsed.rows();
            ObjectNode summary = objectMapper.createObjectNode();
            double min = rows.stream().mapToDouble(MetricRow::accuracy).min().orElseThrow();
            double max = rows.stream().mapToDouble(MetricRow::accuracy).max().orElseThrow();
            double mean = rows.stream().mapToDouble(MetricRow::accuracy).average().orElseThrow();
            summary.put("minAccuracy", min);
            summary.put("maxAccuracy", max);
            summary.put("meanAccuracy", mean);
            summary.put("rowCount", rows.size());
            summary.put("experimentType", definition.experimentTypeId());
            summary.put("algorithmName", definition.algorithm().name());
            summary.put("algorithmVersion", definition.algorithm().version());
            summary.put("classification", definition.algorithm().classification());
            summary.put("algorithmValidated", definition.algorithm().algorithmValidated());
            summary.put("mock", false);
            summary.put("runnerType", "local-matlab");
            summary.put("matlabVersion", "declarative-template");
            summary.put("templateId", definition.templateId());
            ArrayNode metricValues = summary.putArray("metricValues");
            for (MetricDefinition metric : definition.metrics()) {
                ObjectNode metricNode = metricValues.addObject();
                metricNode.put("metricName", metric.metricName());
                metricNode.put("displayName", metric.displayName());
                metricNode.put("unit", metric.unit());
                metricNode.put("sourceColumn", metric.sourceColumn());
                metricNode.put("value", aggregate(parsed, metric));
            }
            String dimensionColumn = parsed.numericColumns().isEmpty()
                    ? "row" : parsed.numericColumns().get(0);
            String metricColumn = firstMetricColumn == null ? dimensionColumn : firstMetricColumn;
            return new ExtractedMetrics(summary, rows, min, max, mean, dimensionColumn, metricColumn);
        } catch (IOException e) {
            throw new ReportDataAssembler.ReportDataException(
                    "Could not extract declarative metrics: " + e.getMessage(), e);
        }
    }

    private double aggregate(ParsedCsv parsed, MetricDefinition metric) {
        List<Double> values = parsed.column(metric.sourceColumn());
        return switch (metric.aggregation()) {
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            case MEAN -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            case LATEST -> values.isEmpty() ? 0.0 : values.get(values.size() - 1);
        };
    }

    private ParsedCsv readCsv(ArtifactRecord artifact, String firstMetricColumn) throws IOException {
        List<String> numericColumns = new ArrayList<>();
        Map<String, Integer> columnIndex = new LinkedHashMap<>();
        List<Map<String, Double>> numericValues = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(
                registry.resolveVerified(artifact.artifactId()), StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new ReportDataAssembler.ReportDataException("accuracy.csv has no header");
            }
            String[] headers = headerLine.split(",", -1);
            for (int index = 0; index < headers.length; index++) {
                columnIndex.put(headers[index].trim(), index);
                numericColumns.add(headers[index].trim());
            }
            String line;
            int row = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                row++;
                String[] fields = line.split(",", -1);
                Map<String, Double> values = new HashMap<>();
                for (String column : numericColumns) {
                    Integer index = columnIndex.get(column);
                    if (index != null && index < fields.length) {
                        try {
                            values.put(column, Double.parseDouble(fields[index].trim()));
                        } catch (NumberFormatException ignored) {
                            // non-numeric columns stay absent from the numeric map
                        }
                    }
                }
                numericValues.add(values);
            }
        }
        List<MetricRow> rows = new ArrayList<>();
        int rowIndex = 0;
        String dimensionColumn = numericColumns.isEmpty() ? null : numericColumns.get(0);
        for (Map<String, Double> values : numericValues) {
            rowIndex++;
            // The dimension is the first column in header order (the declared sweep axis);
            // HashMap iteration order must never decide it.
            double dimension = dimensionColumn == null ? firstNumber(values)
                    : values.getOrDefault(dimensionColumn, firstNumber(values));
            // codeLength carries the truncated dimension value (used only as a trend key);
            // errorRate carries the exact dimension value so citations match the CSV.
            double accuracy = firstMetricColumn == null
                    ? dimension : values.getOrDefault(firstMetricColumn, 0.0);
            rows.add(new MetricRow(rowIndex, (int) dimension, 0, dimension, 0, 0, accuracy,
                    0, 0L, accuracy, 0, 0, 0));
        }
        return new ParsedCsv(rows, numericColumns, numericValues);
    }

    private double firstNumber(Map<String, Double> values) {
        return values.values().stream().findFirst().orElse(0.0);
    }

    private ArtifactRecord require(List<ArtifactRecord> artifacts, ArtifactType type, String jobId) {
        return artifacts.stream().filter(record -> record.type() == type)
                .filter(record -> record.jobId().equals(jobId))
                .findFirst()
                .orElseThrow(() -> new ReportDataAssembler.ReportDataException(
                        "Required artifact is missing: " + type));
    }

    private record ParsedCsv(List<MetricRow> rows, List<String> numericColumns,
                             List<Map<String, Double>> values) {
        List<Double> column(String column) {
            return values.stream().map(map -> map.get(column))
                    .filter(java.util.Objects::nonNull).toList();
        }
    }
}
