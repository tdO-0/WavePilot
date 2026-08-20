package org.example.wavepilot.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.evaluation.ReplayFingerprintService;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.repository.InMemoryExperimentJobRepository;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.experiment.service.ExperimentStateMachine;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.experiment.validation.ResultValidator;
import org.example.wavepilot.runner.ExperimentRunner;
import org.example.wavepilot.runner.ProducedArtifact;
import org.example.wavepilot.runner.RunnerStatus;
import org.example.wavepilot.runner.RunnerSubmission;
import org.example.wavepilot.WavePilotTestFixtures;

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

/**
 * Offline Replay test stack. The DeterministicPolarRunner is a test double that writes the
 * full 13-column polar CSV contract and the validator-compliant summary. Its summary must
 * mirror the real-template contract (runnerType=local-matlab, mock=false) because
 * RealPolarAlgorithmResultValidator hard-codes those values; the truthful runner identity
 * flows through ArtifactRecord.runnerType() (deterministic-test) and the replay manifest.
 */
public final class ReplayTestSupport {

    public static final String CSV_HEADER = "codeLength,trueK,errorRate,correctCount,monteCarloTimes,accuracy,"
            + "sampleCount,randomSeed,meanEstimatedK,mae,bias,runtimeSeconds,algorithmVersion";

    /** Real-format fixture rows (13 columns, no header) for directly constructed source jobs. */
    public static final String FIXTURE_CSV = "32,15,0,10,10,1,50,20,15,0,0,0.01,1.0.0\n"
            + "32,15,0.05,9,10,0.9,50,20,14.9,0.1,-0.1,0.01,1.0.0\n"
            + "32,15,0.1,6,10,0.6,50,20,15.1,0.7,0.1,0.01,1.0.0\n"
            + "64,30,0,10,10,1,50,20,30,0,0,0.01,1.0.0\n"
            + "64,30,0.05,10,10,1,50,20,30,0,0,0.01,1.0.0\n"
            + "64,30,0.1,5,10,0.5,50,20,31,1,1,0.01,1.0.0\n";

    private ReplayTestSupport() { }

    public static Stack stack(Path root) {
        return stack(root, 1.0e-9);
    }

    public static Stack stack(Path root, double replayTolerance) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ArtifactRegistry registry = new ArtifactRegistry(root.toString(), mapper);
        ExperimentSpecValidator specValidator = new ExperimentSpecValidator();
        ExperimentStateMachine stateMachine = new ExperimentStateMachine();
        InMemoryExperimentJobRepository jobRepository = new InMemoryExperimentJobRepository();
        DeterministicPolarRunner runner = new DeterministicPolarRunner(registry, mapper, specValidator);
        ResultValidator resultValidator = new ResultValidator(mapper, specValidator);
        ExperimentService experimentService = new ExperimentService(specValidator, stateMachine,
                jobRepository, runner, registry, resultValidator, mapper);
        ReplayFingerprintService fingerprints = new ReplayFingerprintService(mapper);
        ReplayComparisonEvaluator evaluator = new ReplayComparisonEvaluator(registry, mapper, fingerprints);
        InMemoryReplayRepository replayRepository = new InMemoryReplayRepository();
        ReplayService replayService = new ReplayService(experimentService, registry, fingerprints,
                evaluator, new MatlabTemplateDigest(), replayRepository, mapper, replayTolerance);
        return new Stack(root, mapper, registry, jobRepository, experimentService, runner,
                fingerprints, replayService, replayRepository);
    }

    /** Full-chain job: created through ExperimentService and executed by the deterministic runner. */
    public static ExperimentJob createSucceededJob(Stack stack) throws InterruptedException {
        ExperimentJob job = stack.experimentService().create(WavePilotTestFixtures.validSpec());
        awaitJobTerminal(stack, job.getJobId());
        return stack.experimentService().get(job.getJobId());
    }

    public static ExperimentJob awaitJobTerminal(Stack stack, String jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ExperimentJob job = stack.experimentService().get(jobId);
            if (job.getStatus().isTerminal()) return job;
            Thread.sleep(20);
        }
        throw new AssertionError("Job did not finish in time: " + jobId);
    }

    public static ReplayRecord awaitReplayTerminal(Stack stack, String replayId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ReplayRecord record = stack.replayService().get(replayId);
            if (record.getStatus() != ReplayStatus.RUNNING) return record;
            Thread.sleep(50);
        }
        throw new AssertionError("Replay did not finish in time: " + replayId);
    }

    /** Directly constructed SUCCEEDED job with the given CSV and summary, registered as validated. */
    public static ExperimentJob directSucceededJob(Stack stack, String jobId, String csvContent,
                                            Map<String, Object> summary) throws Exception {
        ExperimentJob job = directJob(stack, jobId);
        job.changeStatus(ExperimentStatus.SUCCEEDED, "fixture validated");
        Path csv = stack.registry().createJobDirectory(jobId).resolve("accuracy.csv");
        Files.writeString(csv, csvContent, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        stack.registry().register(jobId, ArtifactType.ACCURACY_CSV, csv);
        stack.registry().writeJson(jobId, ArtifactType.SUMMARY_JSON, "summary.json", summary);
        stack.registry().markJobValidated(jobId, "local-matlab", false, false,
                "SIMPLIFIED_BASELINE", "polar-k-identification-simple-v1", "1.0.0");
        return job;
    }

    /** Directly constructed job with spec/plan artifacts only; caller controls the status. */
    public static ExperimentJob directJob(Stack stack, String jobId) {
        ExperimentSpec spec = WavePilotTestFixtures.validSpec();
        ExperimentPlan plan = new ExperimentPlan("PLAN-" + jobId, spec,
                "polar-k-identification-simple-v1", 6, List.of("RUN", "VALIDATE"), Instant.now());
        ExperimentJob job = new ExperimentJob(jobId, spec, plan);
        stack.jobRepository().save(job);
        stack.registry().writeJson(jobId, ArtifactType.EXPERIMENT_SPEC, "experiment-spec.json", spec);
        stack.registry().writeJson(jobId, ArtifactType.EXPERIMENT_PLAN, "experiment-plan.json", plan);
        return job;
    }

    public static Map<String, Object> realFormatSummary(ExperimentSpec spec, List<String> csvRows) {
        double sum = 0;
        double min = 1;
        double max = 0;
        for (String line : csvRows) {
            if (line.isBlank()) continue;
            double accuracy = Double.parseDouble(line.split(",", -1)[5]);
            sum += accuracy;
            min = Math.min(min, accuracy);
            max = Math.max(max, accuracy);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("experimentType", "POLAR_CODE_K_IDENTIFICATION");
        summary.put("algorithmName", "polar-bsc-binomial-k-baseline");
        summary.put("algorithmVersion", "1.0.0");
        summary.put("templateVersion", "polar-k-identification-simple-v1");
        summary.put("runnerType", "local-matlab");
        summary.put("errorRateMeaning", "BSC_BIT_FLIP_PROBABILITY");
        summary.put("trueKRule", "15N/32");
        summary.put("classification", "SIMPLIFIED_BASELINE");
        summary.put("mock", false);
        summary.put("algorithmValidated", false);
        summary.put("success", true);
        summary.put("totalPoints", csvRows.size());
        summary.put("completedPoints", csvRows.size());
        summary.put("randomSeed", spec.randomSeed());
        summary.put("minAccuracy", min);
        summary.put("maxAccuracy", max);
        summary.put("meanAccuracy", csvRows.isEmpty() ? 0 : sum / csvRows.size());
        summary.put("totalRuntimeSeconds", csvRows.size() * 0.01);
        summary.put("matlabVersion", "R2023b");
        return summary;
    }

    public record Stack(Path root, ObjectMapper mapper, ArtifactRegistry registry,
                 InMemoryExperimentJobRepository jobRepository, ExperimentService experimentService,
                 DeterministicPolarRunner runner, ReplayFingerprintService fingerprints,
                 ReplayService replayService, InMemoryReplayRepository replayRepository) { }

    /** Deterministic in-process runner producing the 13-column real polar CSV contract. */
    public static final class DeterministicPolarRunner implements ExperimentRunner {

        private final ArtifactRegistry artifactRegistry;
        private final ObjectMapper objectMapper;
        private final ExperimentSpecValidator specValidator;
        private final ExecutorService executor = Executors.newFixedThreadPool(2);
        private final ConcurrentMap<String, Execution> executions = new ConcurrentHashMap<>();
        private volatile double accuracyOffset = 0.0;

        public DeterministicPolarRunner(ArtifactRegistry artifactRegistry, ObjectMapper objectMapper,
                                 ExperimentSpecValidator specValidator) {
            this.artifactRegistry = artifactRegistry;
            this.objectMapper = objectMapper;
            this.specValidator = specValidator;
        }

        void setAccuracyOffset(double accuracyOffset) { this.accuracyOffset = accuracyOffset; }

        @Override
        public RunnerSubmission submit(ExperimentJob job) {
            String externalJobId = "TEST-" + UUID.randomUUID().toString().substring(0, 12);
            Execution execution = new Execution(externalJobId, job.getPlan().totalRuns());
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
            Execution execution = execution(externalJobId);
            execution.cancelled.set(true);
            Future<?> future = execution.future;
            if (future != null) future.cancel(true);
            execution.status = status(execution, RunnerStatus.State.CANCELLED,
                    execution.status.progress(), execution.status.completedRuns(),
                    "Deterministic run cancelled", null);
        }

        @Override
        public List<ProducedArtifact> collectArtifacts(String externalJobId) {
            return List.copyOf(execution(externalJobId).artifacts);
        }

        @Override
        public String runnerType() { return "deterministic-test"; }

        @Override
        public String experimentTemplateVersion() { return "polar-k-identification-simple-v1"; }

        private void execute(ExperimentJob job, Execution execution) {
            Path jobDirectory = artifactRegistry.createJobDirectory(job.getJobId());
            Path csv = jobDirectory.resolve("accuracy.csv");
            Path summary = jobDirectory.resolve("summary.json");
            Path log = jobDirectory.resolve("run.log");
            try {
                execution.status = status(execution, RunnerStatus.State.RUNNING, 0, 0,
                        "Generating deterministic polar data", null);
                ExperimentSpec spec = job.getSpec();
                int ratePoints = specValidator.calculateErrorRatePointCount(spec);
                long totalRows = (long) ratePoints * spec.codeLengths().size();
                Random random = new Random(spec.randomSeed());
                double offset = accuracyOffset;
                StringBuilder csvContent = new StringBuilder(CSV_HEADER).append('\n');
                double sum = 0;
                double min = 1;
                double max = 0;
                long completed = 0;
                for (Integer codeLength : spec.codeLengths()) {
                    for (int point = 0; point < ratePoints; point++) {
                        checkCancellation(execution);
                        BigDecimal errorRate = BigDecimal.valueOf(spec.errorRateStart())
                                .add(BigDecimal.valueOf(spec.errorRateStep()).multiply(BigDecimal.valueOf(point)));
                        int trueK = 15 * codeLength / 32;
                        double base = Math.max(0.05, Math.min(0.95,
                                0.985 - errorRate.doubleValue() * 2.25
                                        - Math.log(codeLength) / Math.log(2) * 0.006));
                        double target = Math.max(0.05, Math.min(0.95,
                                base + offset + (random.nextDouble() - 0.5) * 0.01));
                        int correctCount = (int) Math.round(target * spec.monteCarloTimes());
                        correctCount = Math.max(0, Math.min(spec.monteCarloTimes(), correctCount));
                        double accuracy = spec.monteCarloTimes() == 0
                                ? 0 : (double) correctCount / spec.monteCarloTimes();
                        double estimatedK = trueK + (random.nextDouble() - 0.5) * 2.0;
                        double mae = Math.abs(estimatedK - trueK);
                        double bias = estimatedK - trueK;
                        double runtime = 0.01 + random.nextDouble() * 0.001;
                        csvContent.append(codeLength).append(',').append(trueK).append(',')
                                .append(errorRate.stripTrailingZeros().toPlainString()).append(',')
                                .append(correctCount).append(',').append(spec.monteCarloTimes()).append(',')
                                .append(String.format(java.util.Locale.ROOT, "%.10f", accuracy)).append(',')
                                .append(spec.sampleCount()).append(',').append(spec.randomSeed()).append(',')
                                .append(String.format(java.util.Locale.ROOT, "%.6f", estimatedK)).append(',')
                                .append(String.format(java.util.Locale.ROOT, "%.6f", mae)).append(',')
                                .append(String.format(java.util.Locale.ROOT, "%.6f", bias)).append(',')
                                .append(String.format(java.util.Locale.ROOT, "%.6f", runtime)).append(',')
                                .append("1.0.0").append('\n');
                        completed++;
                        sum += accuracy;
                        min = Math.min(min, accuracy);
                        max = Math.max(max, accuracy);
                        int progress = (int) Math.min(99, completed * 100 / Math.max(1, totalRows));
                        execution.status = status(execution, RunnerStatus.State.RUNNING, progress, completed,
                                "Generated deterministic row " + completed + "/" + totalRows, null);
                    }
                }
                Files.writeString(csv, csvContent, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                List<String> dataRows = new ArrayList<>();
                String[] lines = csvContent.toString().split("\n");
                for (int index = 1; index < lines.length; index++) dataRows.add(lines[index]);
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(summary.toFile(), realFormatSummary(spec, dataRows));
                Files.writeString(log, "DeterministicPolarRunner test double\n"
                        + "No MATLAB process was executed.\nexitCode=0\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                execution.artifacts.add(new ProducedArtifact(ArtifactType.ACCURACY_CSV, csv));
                execution.artifacts.add(new ProducedArtifact(ArtifactType.SUMMARY_JSON, summary));
                execution.artifacts.add(new ProducedArtifact(ArtifactType.RUN_LOG, log));
                execution.status = status(execution, RunnerStatus.State.SUCCEEDED, 100, completed,
                        "Deterministic experiment completed", 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                execution.status = status(execution, RunnerStatus.State.CANCELLED,
                        execution.status.progress(), execution.status.completedRuns(),
                        "Deterministic run cancelled", null);
            } catch (Exception e) {
                execution.status = status(execution, RunnerStatus.State.FAILED,
                        execution.status.progress(), execution.status.completedRuns(),
                        e.getMessage(), 1);
            }
        }

        private void checkCancellation(Execution execution) throws InterruptedException {
            if (execution.cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Deterministic run cancelled");
            }
        }

        private RunnerStatus status(Execution execution, RunnerStatus.State state, int progress,
                                    long completed, String message, Integer exitCode) {
            return new RunnerStatus(execution.externalJobId, state, progress, completed,
                    execution.totalRuns, message, exitCode, Instant.now());
        }

        private Execution execution(String externalJobId) {
            Execution execution = executions.get(externalJobId);
            if (execution == null) {
                throw new NoSuchElementException("Runner job not found: " + externalJobId);
            }
            return execution;
        }

        private static final class Execution {
            private final String externalJobId;
            private final long totalRuns;
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private final List<ProducedArtifact> artifacts =
                    java.util.Collections.synchronizedList(new ArrayList<>());
            private volatile RunnerStatus status;
            private volatile Future<?> future;

            private Execution(String externalJobId, long totalRuns) {
                this.externalJobId = externalJobId;
                this.totalRuns = totalRuns;
                this.status = new RunnerStatus(externalJobId, RunnerStatus.State.QUEUED, 0, 0,
                        totalRuns, "Deterministic run queued", null, Instant.now());
            }
        }
    }
}
