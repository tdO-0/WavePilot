package org.example.wavepilot.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.definition.MetricDefinition;
import org.example.wavepilot.template.definition.OutputContractDefinition;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interprets a validated declarative result into the generic {@link ExperimentResultData}
 * model. The dimension and metric names come from the template definition's output contract
 * (e.g. ebNo/berSim for QPSK BER, erasureProb/berSim for BEC, snr/cpLength for OFDM) —
 * never from the polar codeLength/accuracy compatibility mapping. Every metric value is
 * cited back to its accuracy.csv cell.
 */
@Component
public class DeclarativeResultInterpreter {

    private final ArtifactRegistry registry;
    private final ObjectMapper objectMapper;
    private final ExperimentDefinitionRegistry definitionRegistry;

    public DeclarativeResultInterpreter(ArtifactRegistry registry, ObjectMapper objectMapper,
                                        ExperimentDefinitionRegistry definitionRegistry) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.definitionRegistry = definitionRegistry;
    }

    public ExperimentResultData interpret(ExperimentJob job, List<ArtifactRecord> artifacts) {
        String experimentTypeId = job.getGenericSpec() != null
                ? job.getGenericSpec().experimentTypeId()
                : job.getSpec() == null ? null : job.getSpec().experimentTypeId();
        ExperimentDefinition definition = definitionRegistry.byExperimentTypeId(experimentTypeId)
                .orElseThrow(() -> new ReportDataAssembler.ReportDataException(
                        "No declarative definition for experimentTypeId: " + experimentTypeId));
        OutputContractDefinition outputs = definition.outputs();
        String firstDimension = outputs.requiredColumns().isEmpty()
                ? null : outputs.requiredColumns().get(0);
        List<String> metricColumns = definition.metrics().stream()
                .map(MetricDefinition::sourceColumn).toList();

        ArtifactRecord csv = artifacts.stream()
                .filter(a -> a.type() == ArtifactType.ACCURACY_CSV)
                .findFirst().orElseThrow(() -> new ReportDataAssembler.ReportDataException(
                        "No accuracy.csv artifact for " + job.getJobId()));
        List<ExperimentResultData.MetricSeries> series = new ArrayList<>();
        Map<String, Double> aggregates = new LinkedHashMap<>();
        AtomicInteger ids = new AtomicInteger();
        List<ArtifactCitation> citations = new ArrayList<>();
        List<ReportConclusion> conclusions = new ArrayList<>();
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0;
        int row = 1;
        try (BufferedReader reader = Files.newBufferedReader(registry.resolveVerified(csv.artifactId()),
                StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                throw new ReportDataAssembler.ReportDataException("accuracy.csv has no header");
            }
            String[] columns = header.split(",", -1);
            Map<String, Integer> index = new LinkedHashMap<>();
            for (int i = 0; i < columns.length; i++) index.put(columns[i].trim(), i);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                Map<String, Object> dimensionValues = new LinkedHashMap<>();
                Map<String, Double> metricValues = new LinkedHashMap<>();
                Map<String, String> citationIds = new LinkedHashMap<>();
                for (String column : index.keySet()) {
                    int col = index.get(column);
                    String raw = col < values.length ? values[col].trim() : "";
                    if (metricColumns.contains(column)) {
                        try {
                            double value = Double.parseDouble(raw);
                            metricValues.put(column, value);
                            String citationId = cite(ids, citations, csv, column, row, value);
                            citationIds.put(column, citationId);
                            sum += value;
                            min = Math.min(min, value);
                            max = Math.max(max, value);
                        } catch (NumberFormatException ignored) { /* non-numeric metric cell */ }
                    } else if (column.equals(firstDimension)) {
                        dimensionValues.put(column, parseValue(raw));
                    } else {
                        dimensionValues.put(column, parseValue(raw));
                    }
                }
                if (!metricValues.isEmpty()) {
                    series.add(new ExperimentResultData.MetricSeries(
                            Map.copyOf(dimensionValues), Map.copyOf(metricValues),
                            Map.copyOf(citationIds), row));
                }
                row++;
            }
        } catch (IOException e) {
            throw new ReportDataAssembler.ReportDataException("Cannot read accuracy.csv: " + e.getMessage());
        }
        if (!series.isEmpty()) {
            aggregates.put("min" + metricLabel(metricColumns), min);
            aggregates.put("max" + metricLabel(metricColumns), max);
            aggregates.put("mean" + metricLabel(metricColumns), sum / series.size());
            String firstMetric = metricColumns.isEmpty() ? "metric" : metricColumns.get(0);
            // The citation must point at the row that actually holds the min value.
            final double minValue = min;
            String minCitationId = series.stream()
                    .filter(s -> {
                        Double value = s.metricValues().get(firstMetric);
                        return value != null && Math.abs(value - minValue) < 1e-12;
                    })
                    .map(s -> s.citationIds().get(firstMetric))
                    .filter(id -> id != null && !id.isBlank())
                    .findFirst().orElse(null);
            if (minCitationId != null) {
                conclusions.add(new ReportConclusion("CON-001",
                        "最小" + firstMetric + " 为 " + min, firstMetric, min,
                        List.of(minCitationId), CitationStatus.VERIFIED));
            }
        }
        Map<String, Object> parameters = job.getGenericSpec() != null
                ? job.getGenericSpec().parameters() : Map.of();
        JsonNode summary = readSummary(job.getJobId());
        return new ExperimentResultData(job.getJobId(), experimentTypeId,
                definition.templateId(), definition.version(),
                text(summary, "algorithmName", definition.algorithm().name()),
                text(summary, "algorithmVersion", definition.algorithm().version()),
                text(summary, "classification", definition.algorithm().classification()),
                bool(summary, "mock"), bool(summary, "algorithmValidated"),
                parameters, List.of(firstDimension == null ? "" : firstDimension),
                List.copyOf(metricColumns), List.copyOf(series), Map.copyOf(aggregates),
                text(summary, "runnerType", ""), text(summary, "matlabVersion", ""),
                artifacts.stream().map(a -> new ExperimentResultData.ArtifactView(
                        a.artifactId(), a.type().name(), a.relativePath(), a.sha256(),
                        a.size(), a.mimeType(), a.validated())).toList(),
                List.copyOf(citations), List.copyOf(conclusions));
    }

    private String metricLabel(List<String> metricColumns) {
        return metricColumns.isEmpty() ? "" : metricColumns.get(0).substring(0, 1).toUpperCase()
                + metricColumns.get(0).substring(1);
    }

    private Object parseValue(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private String cite(AtomicInteger ids, List<ArtifactCitation> citations, ArtifactRecord csv,
                        String column, int row, double value) {
        String jobPart = csv.jobId().replaceFirst("^JOB-", "").replaceAll("[^A-Za-z0-9_-]", "_");
        String id = String.format("CIT-%s-%03d", jobPart, ids.incrementAndGet());
        citations.add(new ArtifactCitation(id, csv.jobId(), csv.artifactId(), csv.type(),
                column, Integer.toString(row), value, "", "accuracy.csv row " + row + " field " + column,
                csv.sha256()));
        return id;
    }

    private JsonNode readSummary(String jobId) {
        try {
            ArtifactRecord summary = registry.listByJobId(jobId).stream()
                    .filter(a -> a.type() == ArtifactType.SUMMARY_JSON)
                    .findFirst().orElse(null);
            if (summary == null) return objectMapper.createObjectNode();
            return objectMapper.readTree(registry.resolveVerified(summary.artifactId()).toFile());
        } catch (IOException e) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.asBoolean();
    }
}
