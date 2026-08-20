package org.example.wavepilot.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.evaluation.ReplayFingerprintService;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares the structured results of a source job and its replay job. Scientific
 * reproducibility is judged on the structured CSV and summary (MAT, PNG and logs may
 * differ for environment reasons and are deliberately not byte-compared).
 */
@Component
public class ReplayComparisonEvaluator {

    private final ArtifactRegistry registry;
    private final ObjectMapper objectMapper;
    private final ReplayFingerprintService fingerprints;
    private final ExperimentComparisonMetrics comparisonMetrics;
    private final org.example.wavepilot.template.definition.ExperimentDefinitionRegistry definitionRegistry;

    public ReplayComparisonEvaluator(ArtifactRegistry registry, ObjectMapper objectMapper,
                                     ReplayFingerprintService fingerprints) {
        this(registry, objectMapper, fingerprints, null);
    }

    @Autowired
    public ReplayComparisonEvaluator(ArtifactRegistry registry, ObjectMapper objectMapper,
                                     ReplayFingerprintService fingerprints,
                                     org.example.wavepilot.template.definition.ExperimentDefinitionRegistry definitionRegistry) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.fingerprints = fingerprints;
        this.definitionRegistry = definitionRegistry;
        this.comparisonMetrics = new PolarKComparisonMetrics();
    }

    public ReplayComparisonResult evaluate(ExperimentJob sourceJob, ExperimentJob replayJob,
                                           double tolerance, String replayId) {
        List<String> notes = new ArrayList<>();
        ArtifactRecord sourceCsv = require(sourceJob.getJobId(), ArtifactType.ACCURACY_CSV);
        ArtifactRecord replayCsv = require(replayJob.getJobId(), ArtifactType.ACCURACY_CSV);

        Object sourceSpec = sourceJob.getGenericSpec() != null ? sourceJob.getGenericSpec() : sourceJob.getSpec();
        Object replaySpec = replayJob.getGenericSpec() != null ? replayJob.getGenericSpec() : replayJob.getSpec();
        boolean specConsistent = fingerprints.canonicalJson(sourceSpec)
                .equals(fingerprints.canonicalJson(replaySpec));
        long sourceSeed = sourceJob.getGenericSpec() != null
                ? (sourceJob.getGenericSpec().randomSeed() == null ? 20L : sourceJob.getGenericSpec().randomSeed())
                : sourceJob.getSpec().randomSeed();
        long replaySeed = replayJob.getGenericSpec() != null
                ? (replayJob.getGenericSpec().randomSeed() == null ? 20L : replayJob.getGenericSpec().randomSeed())
                : replayJob.getSpec().randomSeed();
        boolean randomSeedConsistent = sourceSeed == replaySeed;
        boolean runnerTypeConsistent = sourceCsv.runnerType().equals(replayCsv.runnerType());
        boolean templateVersionConsistent = sourceJob.getPlan().experimentTemplateVersion()
                .equals(replayJob.getPlan().experimentTemplateVersion());
        String sourceAlgorithmVersion = summaryText(sourceJob.getJobId(), "algorithmVersion", "unknown");
        String replayAlgorithmVersion = summaryText(replayJob.getJobId(), "algorithmVersion", "unknown");
        boolean algorithmVersionConsistent = sourceAlgorithmVersion.equals(replayAlgorithmVersion);

        ExperimentComparisonMetrics contract = registeredMetrics(sourceJob);
        CsvData sourceData = readCsv(sourceCsv, contract.gridColumns());
        CsvData replayData = readCsv(replayCsv, contract.gridColumns());
        long sourceRows = sourceData.rows().size();
        long replayRows = replayData.rows().size();
        boolean rowCountConsistent = sourceRows == replayRows;
        boolean gridConsistent = sourceData.gridKeys().equals(replayData.gridKeys());

        List<ReplayComparisonResult.MetricComparison> metrics = new ArrayList<>();
        for (ExperimentComparisonMetrics.Metric metric : contract.metrics()) {
            metrics.add(compareNumeric(sourceData, replayData, metric.name(), tolerance, metric.meanAlso()));
        }

        boolean contractConsistent = metrics.stream().noneMatch(metric -> !metric.present()
                && (sourceData.hasColumn(metric.metricName()) != replayData.hasColumn(metric.metricName())));
        if (!contractConsistent) {
            notes.add("source and replay accuracy.csv expose different metric columns");
        }

        boolean withinTolerance = metrics.stream()
                .filter(ReplayComparisonResult.MetricComparison::present)
                .allMatch(ReplayComparisonResult.MetricComparison::withinTolerance);
        boolean consistent = specConsistent && randomSeedConsistent && runnerTypeConsistent
                && templateVersionConsistent && algorithmVersionConsistent
                && rowCountConsistent && gridConsistent && contractConsistent;

        if (!specConsistent) notes.add("canonical ExperimentSpec differs");
        if (!randomSeedConsistent) notes.add("randomSeed differs");
        if (!runnerTypeConsistent) notes.add("runnerType differs");
        if (!templateVersionConsistent) notes.add("templateVersion differs");
        if (!algorithmVersionConsistent) notes.add("algorithmVersion differs");
        if (!rowCountConsistent) notes.add("CSV row count differs (" + sourceRows + " vs " + replayRows + ")");
        if (!gridConsistent) notes.add("parameter grid differs");
        for (ReplayComparisonResult.MetricComparison metric : metrics) {
            if (metric.present() && !metric.withinTolerance()) {
                notes.add(metric.metricName() + " exceeds tolerance (max abs diff "
                        + metric.maxAbsDifference() + " > " + tolerance + ")");
            }
        }
        String message = notes.isEmpty()
                ? "All structured checks passed within tolerance " + tolerance
                : String.join("; ", notes);

        return new ReplayComparisonResult(replayId, sourceJob.getJobId(), replayJob.getJobId(),
                specConsistent, randomSeedConsistent, runnerTypeConsistent, templateVersionConsistent,
                algorithmVersionConsistent, sourceRows, replayRows, rowCountConsistent, gridConsistent,
                metrics, withinTolerance, consistent,
                consistent && withinTolerance ? ReplayComparisonResult.REPRODUCIBLE
                        : ReplayComparisonResult.NOT_REPRODUCIBLE,
                message);
    }

    private ExperimentComparisonMetrics registeredMetrics(ExperimentJob sourceJob) {
        String experimentTypeId = sourceJob.getGenericSpec() != null
                ? sourceJob.getGenericSpec().experimentTypeId()
                : sourceJob.getSpec() == null ? null : sourceJob.getSpec().experimentTypeId();
        if (definitionRegistry != null && experimentTypeId != null
                && definitionRegistry.byExperimentTypeId(experimentTypeId).isPresent()) {
            return new DeclarativeComparisonMetrics(definitionRegistry, experimentTypeId);
        }
        if (sourceJob.getGenericSpec() != null) {
            throw new ReplayComparisonException("No declarative definition is registered for experimentTypeId: "
                    + experimentTypeId);
        }
        if (comparisonMetrics == null || comparisonMetrics.experimentType() != sourceJob.getSpec().experimentType()) {
            throw new ReplayComparisonException("No replay comparison metrics are registered for experiment type: "
                    + sourceJob.getSpec().experimentType() + "; registered: "
                    + org.example.wavepilot.experiment.model.ExperimentType.POLAR_CODE_K_IDENTIFICATION);
        }
        return comparisonMetrics;
    }

    private ReplayComparisonResult.MetricComparison compareNumeric(CsvData source, CsvData replay,
                                                                   String metric, double tolerance,
                                                                   boolean meanAlso) {
        boolean sourceHas = source.hasColumn(metric);
        boolean replayHas = replay.hasColumn(metric);
        if (!sourceHas && !replayHas) {
            return new ReplayComparisonResult.MetricComparison(metric, false, null, null, null, null, true);
        }
        if (!sourceHas || !replayHas) {
            return new ReplayComparisonResult.MetricComparison(metric, false, null, null, null, null, false);
        }
        Map<String, Double> sourceValues = source.numericColumn(metric);
        Map<String, Double> replayValues = replay.numericColumn(metric);
        double maxDiff = 0;
        double sumDiff = 0;
        long compared = 0;
        double sourceAggregate = 0;
        double replayAggregate = 0;
        for (String key : source.gridKeys()) {
            Double left = sourceValues.get(key);
            Double right = replayValues.get(key);
            if (left == null || right == null) continue;
            double diff = Math.abs(left - right);
            maxDiff = Math.max(maxDiff, diff);
            sumDiff += diff;
            compared++;
            sourceAggregate += left;
            replayAggregate += right;
        }
        double meanDiff = compared == 0 ? 0 : sumDiff / compared;
        Double sourceValue = compared == 0 ? null : sourceAggregate / compared;
        Double replayValue = compared == 0 ? null : replayAggregate / compared;
        Double maxAbsDifference = compared == 0 ? 0.0 : maxDiff;
        return new ReplayComparisonResult.MetricComparison(metric, true, sourceValue, replayValue,
                maxAbsDifference, meanAlso ? meanDiff : null, maxAbsDifference <= tolerance);
    }

    private CsvData readCsv(ArtifactRecord artifact, List<String> gridColumns) {
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        List<String> headers = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(
                registry.resolveVerified(artifact.artifactId()), StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new ReplayComparisonException("accuracy.csv has no header for " + artifact.artifactId());
            }
            String[] names = headerLine.split(",", -1);
            headers.addAll(List.of(names));
            String line;
            int rowNumber = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rowNumber++;
                String[] fields = line.split(",", -1);
                Map<String, String> row = new LinkedHashMap<>();
                for (int index = 0; index < names.length && index < fields.length; index++) {
                    row.put(names[index], fields[index].trim());
                }
                rows.put(gridKey(gridColumns, row, rowNumber), row);
            }
        } catch (IOException e) {
            throw new ReplayComparisonException("Cannot read accuracy.csv for " + artifact.artifactId(), e);
        }
        return new CsvData(headers, rows);
    }

    private String gridKey(List<String> gridColumns, Map<String, String> row, int rowNumber) {
        if (gridColumns.isEmpty()) {
            // Declarative templates compare row-aligned (sweep columns are not reliable).
            return "row-" + rowNumber;
        }
        StringBuilder key = new StringBuilder();
        for (String column : gridColumns) {
            String value = row.get(column);
            if (value == null) {
                throw new ReplayComparisonException("accuracy.csv row lacks grid column " + column);
            }
            if (key.length() > 0) key.append('|');
            if ("errorRate".equals(column)) {
                key.append(new BigDecimal(value).stripTrailingZeros().toPlainString());
            } else {
                key.append(value);
            }
        }
        return key.toString();
    }

    private String summaryText(String jobId, String field, String fallback) {
        try {
            ArtifactRecord summary = require(jobId, ArtifactType.SUMMARY_JSON);
            JsonNode node = objectMapper.readTree(registry.resolveVerified(summary.artifactId()).toFile());
            JsonNode value = node.get(field);
            return value == null || !value.isTextual() || value.asText().isBlank()
                    ? fallback : value.asText();
        } catch (IOException e) {
            return fallback;
        }
    }

    private ArtifactRecord require(String jobId, ArtifactType type) {
        return registry.listByJobId(jobId).stream()
                .filter(record -> record.type() == type)
                .findFirst()
                .orElseThrow(() -> new ReplayComparisonException(
                        "Required artifact is missing for " + jobId + ": " + type));
    }

    private record CsvData(List<String> headers, Map<String, Map<String, String>> rows) {
        boolean hasColumn(String column) { return headers.contains(column); }

        Map<String, Double> numericColumn(String column) {
            Map<String, Double> values = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> entry : rows.entrySet()) {
                String raw = entry.getValue().get(column);
                if (raw == null) {
                    throw new ReplayComparisonException("accuracy.csv row lacks field " + column);
                }
                try {
                    values.put(entry.getKey(), Double.parseDouble(raw));
                } catch (NumberFormatException e) {
                    throw new ReplayComparisonException("accuracy.csv field " + column + " is not numeric", e);
                }
            }
            return values;
        }

        Set<String> gridKeys() { return new LinkedHashSet<>(rows.keySet()); }
    }

    public static class ReplayComparisonException extends RuntimeException {
        public ReplayComparisonException(String message) { super(message); }
        public ReplayComparisonException(String message, Throwable cause) { super(message, cause); }
    }
}
