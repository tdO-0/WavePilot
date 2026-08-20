package org.example.wavepilot.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.definition.MetricDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "wavepilot.runner.type", havingValue = "mock", matchIfMissing = true)
public class MockExperimentRunner implements ExperimentRunner {

    private final ArtifactRegistry artifactRegistry;
    private final ObjectMapper objectMapper;
    private final ExperimentSpecValidator specValidator;
    private final ExperimentDefinitionRegistry definitionRegistry;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ConcurrentMap<String, MockExecution> executions = new ConcurrentHashMap<>();

    public MockExperimentRunner(ArtifactRegistry artifactRegistry, ObjectMapper objectMapper,
                                ExperimentSpecValidator specValidator) {
        this(artifactRegistry, objectMapper, specValidator, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MockExperimentRunner(ArtifactRegistry artifactRegistry, ObjectMapper objectMapper,
                                ExperimentSpecValidator specValidator,
                                ExperimentDefinitionRegistry definitionRegistry) {
        this.artifactRegistry = artifactRegistry;
        this.objectMapper = objectMapper;
        this.specValidator = specValidator;
        this.definitionRegistry = definitionRegistry;
    }

    @Override
    public RunnerSubmission submit(ExperimentJob job) {
        String externalJobId = "MOCK-" + UUID.randomUUID().toString().substring(0, 12);
        MockExecution execution = new MockExecution(externalJobId, job.getPlan().totalRuns());
        executions.put(externalJobId, execution);
        execution.future = executor.submit(() -> execute(job, execution));
        return new RunnerSubmission(externalJobId, Instant.now());
    }

    @Override
    public RunnerStatus getStatus(String externalJobId) {
        return execution(externalJobId).status;
    }

    @Override
    public void cancel(String externalJobId) {
        MockExecution execution = execution(externalJobId);
        execution.cancelled.set(true);
        Future<?> future = execution.future;
        if (future != null) {
            future.cancel(true);
        }
        execution.status = status(execution, RunnerStatus.State.CANCELLED,
                execution.status.progress(), execution.status.completedRuns(), "Mock run cancelled", null);
    }

    @Override
    public List<ProducedArtifact> collectArtifacts(String externalJobId) {
        return List.copyOf(execution(externalJobId).artifacts);
    }

    @Override
    public String runnerType() {
        return "mock";
    }

    @Override
    public String experimentTemplateVersion() {
        return "mock-polar-k-v1";
    }

    private void execute(ExperimentJob job, MockExecution execution) {
        Path jobDirectory = artifactRegistry.createJobDirectory(job.getJobId());
        Path csv = jobDirectory.resolve("accuracy.csv");
        Path summary = jobDirectory.resolve("summary.json");
        Path log = jobDirectory.resolve("run.log");
        try {
            execution.status = status(execution, RunnerStatus.State.RUNNING, 0, 0,
                    "Generating deterministic mock experiment data", null);
            ExperimentSpec spec = job.getSpec();
            ExperimentDefinition definition = spec != null ? resolveDefinition(spec) : null;
            // Generic (declarative-template) job: the grid comes from the runtime parameters.
            int ratePoints;
            long totalRows;
            if (spec == null && job.getGenericSpec() != null) {
                org.example.wavepilot.template.definition.ExperimentDefinition genericDefinition =
                        definitionRegistry == null ? null : definitionRegistry
                                .byExperimentTypeId(job.getGenericSpec().experimentTypeId()).orElse(null);
                if (genericDefinition != null) definition = genericDefinition;
                long points = genericPoints(job.getGenericSpec());
                ratePoints = (int) Math.max(1, points);
                totalRows = ratePoints;
            } else {
                ratePoints = specValidator.calculateErrorRatePointCount(spec);
                totalRows = (long) ratePoints * spec.codeLengths().size();
            }
            long randomSeed = spec != null ? spec.randomSeed() : 20L;
            StringBuilder csvContent = new StringBuilder();
            Map<String, Object> summaryData = new LinkedHashMap<>();
            Random random = new Random(randomSeed);
            double sum = 0;
            double min = 1;
            double max = 0;
            long completed = 0;

            // Declarative templates: the real MATLAB script writes the definition's declared
            // columns; the mock mirrors that contract so result validation and the report
            // chain see the same shape offline as online. Built-in polar jobs get the full
            // 13-column polar contract (the real MATLAB runner's output).
            List<String> columns = definition == null ? List.of()
                    : definition.outputs().requiredColumns();
            List<String> metricColumns = definition == null ? List.of()
                    : definition.metrics().stream().map(MetricDefinition::sourceColumn).toList();
            boolean polarContract = definition == null;
            csvContent.append(polarContract
                    ? "codeLength,trueK,errorRate,correctCount,monteCarloTimes,accuracy,sampleCount,"
                            + "randomSeed,meanEstimatedK,mae,bias,runtimeSeconds,algorithmVersion\n"
                    : String.join(",", columns)).append('\n');

            java.util.List<Integer> codeLengths = spec == null ? List.of(1) : spec.codeLengths();
            for (Integer codeLength : codeLengths) {
                for (int point = 0; point < ratePoints; point++) {
                    checkCancellation(execution);
                    BigDecimal errorRate = spec == null
                            ? BigDecimal.valueOf(point)
                            : BigDecimal.valueOf(spec.errorRateStart())
                                    .add(BigDecimal.valueOf(spec.errorRateStep()).multiply(BigDecimal.valueOf(point)));
                    double accuracy = mockAccuracy(codeLength, errorRate.doubleValue(), random);
                    // The CSV carries the rounded value; summary statistics must use the same
                    // value so report citations match the artifact within 1e-9.
                    double roundedAccuracy = Double.parseDouble(
                            String.format(java.util.Locale.ROOT, "%.6f", accuracy));
                    if (polarContract) {
                        csvContent.append(polarRow(codeLength, errorRate, roundedAccuracy,
                                spec, random)).append('\n');
                    } else {
                        csvContent.append(declarativeRow(columns, metricColumns, codeLength, errorRate,
                                roundedAccuracy, point, random)).append('\n');
                    }
                    completed++;
                    sum += roundedAccuracy;
                    min = Math.min(min, roundedAccuracy);
                    max = Math.max(max, roundedAccuracy);
                    int progress = (int) Math.min(99, completed * 100 / Math.max(1, totalRows));
                    execution.status = status(execution, RunnerStatus.State.RUNNING, progress, completed,
                            "Generated mock result row " + completed + "/" + totalRows, null);
                    if (completed % 25 == 0) {
                        Thread.sleep(2);
                    }
                }
            }

            Files.writeString(csv, csvContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            summaryData.put("mock", true);
            summaryData.put("jobId", job.getJobId());
            summaryData.put("rowCount", completed);
            summaryData.put("averageAccuracy", completed == 0 ? 0 : sum / completed);
            summaryData.put("minAccuracy", min);
            summaryData.put("maxAccuracy", max);
            summaryData.put("meanAccuracy", completed == 0 ? 0 : sum / completed);
            summaryData.put("randomSeed", randomSeed);
            if (definition != null) {
                // Declarative contract requires these summary fields (jsonRequiredFields).
                summaryData.put("experimentType", definition.experimentTypeId());
                summaryData.put("algorithmName", definition.algorithm().name());
                summaryData.put("algorithmVersion", definition.algorithm().version());
            } else {
                // Built-in polar jobs: the report chain reads these summary fields.
                summaryData.put("experimentType", "POLAR_CODE_K_IDENTIFICATION");
                summaryData.put("algorithmName", "polar-bsc-binomial-k-baseline");
                summaryData.put("algorithmVersion", "1.0.0");
                summaryData.put("classification", "SIMPLIFIED_BASELINE");
                summaryData.put("algorithmValidated", false);
                summaryData.put("matlabVersion", "R2023b");
                summaryData.put("runnerType", "mock");
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(summary.toFile(), summaryData);
            Files.writeString(log,
                    "WavePilot MockExperimentRunner\nNo MATLAB process was executed.\nexitCode=0\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            execution.artifacts.add(new ProducedArtifact(ArtifactType.ACCURACY_CSV, csv));
            execution.artifacts.add(new ProducedArtifact(ArtifactType.SUMMARY_JSON, summary));
            execution.artifacts.add(new ProducedArtifact(ArtifactType.RUN_LOG, log));
            execution.status = status(execution, RunnerStatus.State.SUCCEEDED, 100, completed,
                    "Mock experiment completed", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            execution.status = status(execution, RunnerStatus.State.CANCELLED,
                    execution.status.progress(), execution.status.completedRuns(), "Mock run cancelled", null);
        } catch (Exception e) {
            try {
                Files.writeString(log, "Mock runner failed: " + e.getMessage(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                execution.artifacts.add(new ProducedArtifact(ArtifactType.RUN_LOG, log));
            } catch (IOException ignored) {
                // The original failure is more useful to the caller.
            }
            execution.status = status(execution, RunnerStatus.State.FAILED,
                    execution.status.progress(), execution.status.completedRuns(), e.getMessage(), 1);
        }
    }

    private ExperimentDefinition resolveDefinition(ExperimentSpec spec) {
        if (definitionRegistry == null || spec.experimentTypeId() == null) return null;
        return definitionRegistry.byExperimentTypeId(spec.experimentTypeId()).orElse(null);
    }

    /** Point count of a generic spec's first numeric sweep group (start/end/step). */
    private long genericPoints(org.example.wavepilot.experiment.model.GenericExperimentSpec spec) {
        Double start = genericNumber(spec.parameter("ebNoStart") != null ? spec.parameter("ebNoStart")
                : spec.parameter("snrStart"));
        Double end = genericNumber(spec.parameter("ebNoEnd") != null ? spec.parameter("ebNoEnd")
                : spec.parameter("snrEnd"));
        Double step = genericNumber(spec.parameter("ebNoStep") != null ? spec.parameter("ebNoStep")
                : spec.parameter("snrStep"));
        if (start == null || end == null || step == null || step <= 0) return 1;
        return (long) Math.floor((end - start) / step) + 1;
    }

    private Double genericNumber(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    /** One CSV row for a declarative template: first column carries the sweep dimension, metric columns carry the accuracy, remaining numeric columns a deterministic secondary value. */
    private String declarativeRow(List<String> columns, List<String> metricColumns, int codeLength,
                                  BigDecimal errorRate, double accuracy, int point, Random random) {
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) row.append(',');
            String column = columns.get(index);
            if (index == 0) {
                row.append(errorRate.stripTrailingZeros().toPlainString());
            } else if (metricColumns.contains(column)) {
                row.append(String.format(java.util.Locale.ROOT, "%.6f", accuracy));
            } else {
                row.append(String.format(java.util.Locale.ROOT, "%.4f",
                        Math.abs((codeLength + point + index) % 7) * 0.125 + random.nextDouble() * 0.01));
            }
        }
        return row.toString();
    }

    /** One row of the full 13-column polar contract the real MATLAB runner produces. */
    private String polarRow(int codeLength, BigDecimal errorRate, double accuracy,
                            ExperimentSpec spec, Random random) {
        int trueK = Math.max(1, codeLength / 2);
        int correctCount = (int) Math.round(accuracy * spec.sampleCount());
        int trials = spec.monteCarloTimes();
        return codeLength + "," + trueK + ","
                + errorRate.stripTrailingZeros().toPlainString() + "," + correctCount + ","
                + trials + ","
                + String.format(java.util.Locale.ROOT, "%.6f", accuracy) + ","
                + spec.sampleCount() + "," + spec.randomSeed() + ","
                + trueK + ",0.10,0.05,0.01,mock-1.0.0";
    }

    private double mockAccuracy(int codeLength, double errorRate, Random random) {
        double lengthPenalty = Math.log(codeLength) / Math.log(2) * 0.006;
        double jitter = (random.nextDouble() - 0.5) * 0.01;
        return Math.max(0, Math.min(1, 0.985 - errorRate * 2.25 - lengthPenalty + jitter));
    }

    private void checkCancellation(MockExecution execution) throws InterruptedException {
        if (execution.cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Mock run cancelled");
        }
    }

    private RunnerStatus status(MockExecution execution, RunnerStatus.State state, int progress,
                                long completed, String message, Integer exitCode) {
        return new RunnerStatus(execution.externalJobId, state, progress, completed,
                execution.totalRuns, message, exitCode, Instant.now());
    }

    private MockExecution execution(String externalJobId) {
        MockExecution execution = executions.get(externalJobId);
        if (execution == null) {
            throw new NoSuchElementException("Runner job not found: " + externalJobId);
        }
        return execution;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class MockExecution {
        private final String externalJobId;
        private final long totalRuns;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<ProducedArtifact> artifacts = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile RunnerStatus status;
        private volatile Future<?> future;

        private MockExecution(String externalJobId, long totalRuns) {
            this.externalJobId = externalJobId;
            this.totalRuns = totalRuns;
            this.status = new RunnerStatus(externalJobId, RunnerStatus.State.QUEUED, 0, 0,
                    totalRuns, "Mock run queued", null, Instant.now());
        }
    }
}
