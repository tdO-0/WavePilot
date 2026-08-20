package org.example.wavepilot.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentType;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Polar-k metrics extraction: parses the 13-column accuracy.csv contract and cross-checks
 * summary.json min/max/mean against a Java recomputation of the CSV.
 */
public class PolarKExperimentMetricsExtractor implements ExperimentMetricsExtractor {

    private static final double TOLERANCE = 1.0e-9;

    private final ArtifactRegistry registry;
    private final ObjectMapper objectMapper;

    public PolarKExperimentMetricsExtractor(ArtifactRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExperimentType experimentType() {
        return ExperimentType.POLAR_CODE_K_IDENTIFICATION;
    }

    @Override
    public ExtractedMetrics extract(ArtifactRegistry extractRegistry, ExperimentJob job,
                                    List<ArtifactRecord> artifacts) {
        ArtifactRecord csv = require(artifacts, ArtifactType.ACCURACY_CSV, job.getJobId());
        ArtifactRecord summaryArtifact = require(artifacts, ArtifactType.SUMMARY_JSON, job.getJobId());
        try {
            JsonNode summary = objectMapper.readTree(
                    extractRegistry.resolveVerified(summaryArtifact.artifactId()).toFile());
            List<MetricRow> rows = readCsv(csv);
            if (rows.isEmpty()) throw new ReportDataAssembler.ReportDataException(
                    "accuracy.csv contains no result rows");
            double min = rows.stream().mapToDouble(MetricRow::accuracy).min().orElseThrow();
            double max = rows.stream().mapToDouble(MetricRow::accuracy).max().orElseThrow();
            double mean = rows.stream().mapToDouble(MetricRow::accuracy).average().orElseThrow();
            requireSummaryMetric(summary, "minAccuracy", min);
            requireSummaryMetric(summary, "maxAccuracy", max);
            requireSummaryMetric(summary, "meanAccuracy", mean);
            return new ExtractedMetrics(summary, rows, min, max, mean, null, null);
        } catch (IOException e) {
            throw new ReportDataAssembler.ReportDataException(
                    "Could not extract metrics: " + e.getMessage(), e);
        }
    }

    private List<MetricRow> readCsv(ArtifactRecord artifact) throws IOException {
        List<MetricRow> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(
                registry.resolveVerified(artifact.artifactId()), StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            String expected = "codeLength,trueK,errorRate,correctCount,monteCarloTimes,accuracy,sampleCount,"
                    + "randomSeed,meanEstimatedK,mae,bias,runtimeSeconds,algorithmVersion";
            if (!expected.equals(header)) throw new ReportDataAssembler.ReportDataException(
                    "Unsupported accuracy.csv contract");
            String line;
            int row = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                row++;
                String[] f = line.split(",", -1);
                if (f.length != 13) throw new ReportDataAssembler.ReportDataException(
                        "Invalid accuracy.csv row " + row);
                rows.add(new MetricRow(row, Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                        new BigDecimal(f[2]).doubleValue(), Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                        Double.parseDouble(f[5]), Integer.parseInt(f[6]), Long.parseLong(f[7]),
                        Double.parseDouble(f[8]), Double.parseDouble(f[9]), Double.parseDouble(f[10]),
                        Double.parseDouble(f[11])));
            }
        }
        return rows;
    }

    private void requireSummaryMetric(JsonNode summary, String field, double actual) {
        JsonNode value = summary.get(field);
        if (value == null || !value.isNumber() || Math.abs(value.asDouble() - actual) > TOLERANCE) {
            throw new ReportDataAssembler.ReportDataException(
                    "summary.json " + field + " does not match accuracy.csv");
        }
    }

    private ArtifactRecord require(List<ArtifactRecord> artifacts, ArtifactType type, String jobId) {
        return artifacts.stream().filter(record -> record.type() == type)
                .filter(record -> record.jobId().equals(jobId))
                .findFirst()
                .orElseThrow(() -> new ReportDataAssembler.ReportDataException(
                        "Required artifact is missing: " + type));
    }
}
