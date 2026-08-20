package org.example.wavepilot.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ReportDataAssembler {

    private final ArtifactRegistry registry;
    private final ObjectMapper objectMapper;
    private final ExperimentMetricsExtractor polarExtractor;
    private final ExperimentDefinitionRegistry definitionRegistry;

    public ReportDataAssembler(ArtifactRegistry registry, ObjectMapper objectMapper) {
        this(registry, objectMapper, null);
    }

    @Autowired
    public ReportDataAssembler(ArtifactRegistry registry, ObjectMapper objectMapper,
                               ExperimentDefinitionRegistry definitionRegistry) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.definitionRegistry = definitionRegistry;
        this.polarExtractor = new PolarKExperimentMetricsExtractor(registry, objectMapper);
    }

    public ExperimentReportData assemble(ExperimentJob job, List<ArtifactRecord> artifacts) {
        if (job.getStatus() != ExperimentStatus.SUCCEEDED) {
            throw new ReportDataException("Job must be SUCCEEDED before report generation: " + job.getJobId());
        }
        ArtifactRecord csv = require(artifacts, ArtifactType.ACCURACY_CSV, job.getJobId());
        ArtifactRecord summaryArtifact = require(artifacts, ArtifactType.SUMMARY_JSON, job.getJobId());
        ArtifactRecord specArtifact = require(artifacts, ArtifactType.EXPERIMENT_SPEC, job.getJobId());
        ArtifactRecord planArtifact = require(artifacts, ArtifactType.EXPERIMENT_PLAN, job.getJobId());
        if (artifacts.stream().anyMatch(record -> !record.validated())) {
            throw new ReportDataException("All report source artifacts must have passed ResultValidator");
        }
        ExperimentMetricsExtractor extractor = registeredExtractor(job);

        ExperimentMetricsExtractor.ExtractedMetrics extracted =
                    extractor.extract(registry, job, artifacts);
            JsonNode summary = extracted.summary();
            List<ExperimentMetricsExtractor.MetricRow> rows = extracted.rows();
            double min = extracted.minAccuracy();
            double max = extracted.maxAccuracy();
            double mean = extracted.meanAccuracy();

            AtomicInteger ids = new AtomicInteger();
            List<ArtifactCitation> citations = new ArrayList<>();
            Map<String, String> configCitations = new LinkedHashMap<>();
            ExperimentSpec spec = job.getSpec();
            if (job.getGenericSpec() != null) {
                // Generic (declarative-template) job: cite the actual parameter map — no
                // fake polar codeLengths/errorRate fields in the config citations.
                for (Map.Entry<String, Object> entry : job.getGenericSpec().parameters().entrySet()) {
                    configCitations.put(entry.getKey(), jsonCitation(ids, citations, specArtifact,
                            "parameters." + entry.getKey(), entry.getValue(), "", entry.getKey()));
                }
                configCitations.put("randomSeed", jsonCitation(ids, citations, specArtifact,
                        "randomSeed", job.getGenericSpec().randomSeed(), "", "Random seed"));
                configCitations.put("totalPoints", jsonCitation(ids, citations, planArtifact,
                        "totalRuns", job.getPlan().totalRuns(), "parameter points", "Planned parameter points"));
            } else {
                configCitations.put("codeLengths", jsonCitation(ids, citations, specArtifact,
                        "codeLengths", spec.codeLengths(), "", "Configured polar code lengths"));
                configCitations.put("errorRateStart", jsonCitation(ids, citations, specArtifact,
                        "errorRateStart", spec.errorRateStart(), "BSC probability", "BSC sweep start"));
                configCitations.put("errorRateEnd", jsonCitation(ids, citations, specArtifact,
                        "errorRateEnd", spec.errorRateEnd(), "BSC probability", "BSC sweep end"));
                configCitations.put("errorRateStep", jsonCitation(ids, citations, specArtifact,
                        "errorRateStep", spec.errorRateStep(), "BSC probability", "BSC sweep step"));
                configCitations.put("sampleCount", jsonCitation(ids, citations, specArtifact,
                        "sampleCount", spec.sampleCount(), "codewords/trial", "Intercepted complete codewords M"));
                configCitations.put("monteCarloTimes", jsonCitation(ids, citations, specArtifact,
                        "monteCarloTimes", spec.monteCarloTimes(), "trials/point", "Independent repetitions T"));
                configCitations.put("randomSeed", jsonCitation(ids, citations, specArtifact,
                        "randomSeed", spec.randomSeed(), "", "Random seed"));
                configCitations.put("totalPoints", jsonCitation(ids, citations, planArtifact,
                        "totalRuns", job.getPlan().totalRuns(), "parameter points", "Planned parameter points"));
            }

            List<ExperimentReportData.AccuracyPoint> points = new ArrayList<>();
            for (ExperimentMetricsExtractor.MetricRow row : rows) {
                points.add(toPoint(ids, citations, csv, row, extracted));
            }

            String minCitation = jsonCitation(ids, citations, summaryArtifact, "minAccuracy", min,
                    "ratio", "Minimum accuracy");
            String maxCitation = jsonCitation(ids, citations, summaryArtifact, "maxAccuracy", max,
                    "ratio", "Maximum accuracy");
            String meanCitation = jsonCitation(ids, citations, summaryArtifact, "meanAccuracy", mean,
                    "ratio", "Mean accuracy");

            // Declarative templates have no polar code-length semantics: trend keys come
            // from the actual row dimension instead of spec.codeLengths().
            List<Integer> trendKeys = (job.getGenericSpec() != null || job.getSpec().experimentTypeId() != null)
                    ? rows.stream().map(ExperimentMetricsExtractor.MetricRow::codeLength)
                            .distinct().sorted().toList()
                    : spec.codeLengths();
            List<ExperimentReportData.CodeLengthTrend> trends = trendKeys.stream()
                    .map(n -> trend(n, points)).toList();
            List<ReportConclusion> conclusions = conclusions(points, min, max, mean,
                    minCitation, maxCitation, meanCitation);
            List<ExperimentReportData.ArtifactView> views = artifacts.stream()
                    .map(record -> new ExperimentReportData.ArtifactView(record.artifactId(), record.type(),
                            record.relativePath(), record.sha256(), record.size(), record.mimeType(), record.validated()))
                    .toList();

            return new ExperimentReportData(job.getJobId(), text(summary, "experimentType"),
                    text(summary, "algorithmName"), text(summary, "algorithmVersion"),
                    text(summary, "classification"), bool(summary, "mock"),
                    bool(summary, "algorithmValidated"),
                    job.getGenericSpec() != null ? List.of() : spec.codeLengths(),
                    new ExperimentReportData.ErrorRateRange(
                            job.getGenericSpec() != null ? 0 : spec.errorRateStart(),
                            job.getGenericSpec() != null ? 0 : spec.errorRateEnd(),
                            job.getGenericSpec() != null ? 0 : spec.errorRateStep()),
                    job.getGenericSpec() != null ? 0 : spec.sampleCount(),
                    job.getGenericSpec() != null ? 0 : spec.monteCarloTimes(),
                    job.getGenericSpec() != null ? 20L : spec.randomSeed(),
                    job.getPlan().totalRuns(),
                    new ExperimentReportData.AccuracySummary(min, max, mean,
                            minCitation, maxCitation, meanCitation), trends, points,
                    text(summary, "matlabVersion"), text(summary, "runnerType"),
                    job.getPlan().experimentTemplateVersion(), views, configCitations, citations, conclusions);
    }

    private ExperimentReportData.AccuracyPoint toPoint(AtomicInteger ids, List<ArtifactCitation> citations,
                                                        ArtifactRecord csv,
                                                        ExperimentMetricsExtractor.MetricRow row,
                                                        ExperimentMetricsExtractor.ExtractedMetrics extracted) {
        Map<String, String> refs = new LinkedHashMap<>();
        if (extracted.dimensionColumn() != null && !"codeLength".equals(extracted.dimensionColumn())) {
            // Declarative CSV: cite the real column names instead of the polar field names.
            // The exact dimension value rides in errorRate (see DeclarativeMetricsExtractor).
            refs.put("codeLength", csvCitation(ids, citations, csv, row.row(),
                    extracted.dimensionColumn(), row.errorRate(), "dimension"));
            refs.put("accuracy", csvCitation(ids, citations, csv, row.row(),
                    extracted.metricColumn(), row.accuracy(), "ratio"));
        } else {
            refs.put("codeLength", csvCitation(ids, citations, csv, row.row(), "codeLength", row.codeLength(), "bits"));
            refs.put("trueK", csvCitation(ids, citations, csv, row.row(), "trueK", row.trueK(), "bits"));
            refs.put("errorRate", csvCitation(ids, citations, csv, row.row(), "errorRate", row.errorRate(), "BSC probability"));
            refs.put("correctCount", csvCitation(ids, citations, csv, row.row(), "correctCount", row.correctCount(), "trials"));
            refs.put("accuracy", csvCitation(ids, citations, csv, row.row(), "accuracy", row.accuracy(), "ratio"));
            refs.put("meanEstimatedK", csvCitation(ids, citations, csv, row.row(), "meanEstimatedK", row.meanEstimatedK(), "bits"));
            refs.put("mae", csvCitation(ids, citations, csv, row.row(), "mae", row.mae(), "bits"));
            refs.put("bias", csvCitation(ids, citations, csv, row.row(), "bias", row.bias(), "bits"));
        }
        return new ExperimentReportData.AccuracyPoint(row.row(), row.codeLength(), row.trueK(), row.errorRate(),
                row.correctCount(), row.monteCarloTimes(), row.accuracy(), row.sampleCount(), row.randomSeed(),
                row.meanEstimatedK(), row.mae(), row.bias(), row.runtimeSeconds(), refs);
    }

    private List<ReportConclusion> conclusions(List<ExperimentReportData.AccuracyPoint> points,
                                               double min, double max, double mean,
                                               String minCitation, String maxCitation, String meanCitation) {
        List<ReportConclusion> result = new ArrayList<>();
        result.add(conclusion("CON-001", "实验参数点的最小识别准确率为 " + min,
                "minAccuracy", min, minCitation));
        result.add(conclusion("CON-002", "实验参数点的最大识别准确率为 " + max,
                "maxAccuracy", max, maxCitation));
        result.add(conclusion("CON-003", "全部参数点的平均识别准确率为 " + mean,
                "meanAccuracy", mean, meanCitation));
        int index = 4;
        for (ExperimentReportData.AccuracyPoint point : points) {
            result.add(conclusion(String.format("CON-%03d", index++),
                    "当 N=" + point.codeLength() + "、BSC ε=" + point.errorRate()
                            + " 时，识别准确率为 " + point.accuracy(),
                    "accuracy", point.accuracy(), point.citationIds().get("accuracy")));
        }
        return result;
    }

    private ReportConclusion conclusion(String id, String text, String metric, Number value, String citationId) {
        return new ReportConclusion(id, text, metric, value, List.of(citationId), CitationStatus.VERIFIED);
    }

    private ExperimentReportData.CodeLengthTrend trend(int n, List<ExperimentReportData.AccuracyPoint> points) {
        List<ExperimentReportData.AccuracyPoint> matching = points.stream()
                .filter(point -> point.codeLength() == n).toList();
        if (matching.isEmpty()) throw new ReportDataException("No accuracy rows exist for codeLength=" + n);
        Comparator<ExperimentReportData.AccuracyPoint> comparator = Comparator
                .comparingDouble(ExperimentReportData.AccuracyPoint::accuracy)
                .thenComparingDouble(ExperimentReportData.AccuracyPoint::errorRate);
        return new ExperimentReportData.CodeLengthTrend(n,
                matching.stream().max(comparator).orElseThrow(),
                matching.stream().min(comparator).orElseThrow());
    }

    private String csvCitation(AtomicInteger ids, List<ArtifactCitation> citations, ArtifactRecord artifact,
                               int row, String field, Object value, String unit) {
        return addCitation(ids, citations, artifact, field, Integer.toString(row), value, unit,
                "accuracy.csv row " + row + " field " + field);
    }

    private String jsonCitation(AtomicInteger ids, List<ArtifactCitation> citations, ArtifactRecord artifact,
                                String field, Object value, String unit, String description) {
        return addCitation(ids, citations, artifact, field, "$", value, unit, description);
    }

    private String addCitation(AtomicInteger ids, List<ArtifactCitation> citations, ArtifactRecord artifact,
                               String field, String row, Object value, String unit, String description) {
        String jobPart = artifact.jobId().replaceFirst("^JOB-", "").replaceAll("[^A-Za-z0-9_-]", "_");
        String id = String.format("CIT-%s-%03d", jobPart, ids.incrementAndGet());
        citations.add(new ArtifactCitation(id, artifact.jobId(), artifact.artifactId(), artifact.type(),
                field, row, value, unit, description, artifact.sha256()));
        return id;
    }

    private ExperimentMetricsExtractor registeredExtractor(ExperimentJob job) {
        String experimentTypeId = job.getGenericSpec() != null
                ? job.getGenericSpec().experimentTypeId()
                : job.getSpec() == null ? null : job.getSpec().experimentTypeId();
        if (definitionRegistry != null && experimentTypeId != null
                && definitionRegistry.byExperimentTypeId(experimentTypeId).isPresent()) {
            return new DeclarativeMetricsExtractor(definitionRegistry, registry, objectMapper);
        }
        if (job.getGenericSpec() != null) {
            throw new ReportDataException("No declarative definition is registered for experimentTypeId: "
                    + experimentTypeId);
        }
        ExperimentType type = job.getSpec().experimentType();
        if (polarExtractor == null || polarExtractor.experimentType() != type) {
            throw new ReportDataException("No report metrics extractor is registered for experiment type: "
                    + type + "; registered: " + ExperimentType.POLAR_CODE_K_IDENTIFICATION);
        }
        return polarExtractor;
    }

    private ArtifactRecord require(List<ArtifactRecord> artifacts, ArtifactType type, String jobId) {
        return artifacts.stream().filter(record -> record.type() == type).findFirst()
                .filter(record -> record.jobId().equals(jobId))
                .orElseThrow(() -> new ReportDataException("Required artifact is missing: " + type));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new ReportDataException("summary.json field is missing: " + field);
        return value.asText();
    }

    private boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean())
            throw new ReportDataException("summary.json boolean field is missing: " + field);
        return value.asBoolean();
    }

    public static class ReportDataException extends RuntimeException {
        public ReportDataException(String message) { super(message); }
        public ReportDataException(String message, Throwable cause) { super(message, cause); }
    }
}
