package org.example.wavepilot.experiment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.ExperimentGridResolver;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentProgress;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.GenericExperimentSpec;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.experiment.validation.ResultValidator;
import org.example.wavepilot.runner.ExperimentRunner;
import org.example.wavepilot.runner.ProducedArtifact;
import org.example.wavepilot.runner.RunnerStatus;
import org.example.wavepilot.runner.RunnerSubmission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ExperimentService {

    private final ExperimentSpecValidator specValidator;
    private final ExperimentStateMachine stateMachine;
    private final ExperimentJobRepository repository;
    private final ExperimentRunner runner;
    private final ArtifactRegistry artifactRegistry;
    private final ResultValidator resultValidator;
    private final ObjectMapper objectMapper;
    private final org.example.wavepilot.template.TemplateRegistry templateRegistry;
    private final org.example.wavepilot.template.definition.ExperimentDefinitionRegistry definitionRegistry;
    private final ExperimentGridResolver gridResolver;
    private final ExecutorService orchestrationExecutor = Executors.newFixedThreadPool(4);
    private final ConcurrentMap<String, String> idempotentJobs = new ConcurrentHashMap<>();

    public ExperimentService(ExperimentSpecValidator specValidator, ExperimentStateMachine stateMachine,
                             ExperimentJobRepository repository, ExperimentRunner runner,
                             ArtifactRegistry artifactRegistry, ResultValidator resultValidator,
                             ObjectMapper objectMapper) {
        this(specValidator, stateMachine, repository, runner, artifactRegistry, resultValidator,
                objectMapper, null, null);
    }

    public ExperimentService(ExperimentSpecValidator specValidator, ExperimentStateMachine stateMachine,
                             ExperimentJobRepository repository, ExperimentRunner runner,
                             ArtifactRegistry artifactRegistry, ResultValidator resultValidator,
                             ObjectMapper objectMapper,
                             org.example.wavepilot.template.TemplateRegistry templateRegistry) {
        this(specValidator, stateMachine, repository, runner, artifactRegistry, resultValidator,
                objectMapper, templateRegistry, null);
    }

    @Autowired
    public ExperimentService(ExperimentSpecValidator specValidator, ExperimentStateMachine stateMachine,
                             ExperimentJobRepository repository, ExperimentRunner runner,
                             ArtifactRegistry artifactRegistry, ResultValidator resultValidator,
                             ObjectMapper objectMapper,
                             org.example.wavepilot.template.TemplateRegistry templateRegistry,
                             org.example.wavepilot.template.definition.ExperimentDefinitionRegistry definitionRegistry) {
        this.specValidator = specValidator;
        this.stateMachine = stateMachine;
        this.repository = repository;
        this.runner = runner;
        this.artifactRegistry = artifactRegistry;
        this.resultValidator = resultValidator;
        this.objectMapper = objectMapper;
        this.templateRegistry = templateRegistry;
        this.definitionRegistry = definitionRegistry;
        this.gridResolver = new ExperimentGridResolver();
    }

    public ValidationResult parseAndValidate(ExperimentSpec spec) {
        return specValidator.validate(spec);
    }

    public ExperimentJob create(ExperimentSpec spec) {
        return createInternal(spec);
    }

    /**
     * Controlled idempotent entry point for durable AgentRun execution. The key never reaches
     * the Runner and cannot alter the ExperimentSpec; duplicate calls reuse the first job.
     */
    public ExperimentJob create(ExperimentSpec spec, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return createInternal(spec);
        synchronized (idempotentJobs) {
            String existing = idempotentJobs.get(idempotencyKey);
            if (existing != null) return get(existing);
            ExperimentJob created = createInternal(spec);
            idempotentJobs.put(idempotencyKey, created.getJobId());
            return created;
        }
    }

    private ExperimentJob createInternal(ExperimentSpec spec) {
        ValidationResult validation = specValidator.validate(spec);
        if (!validation.valid()) {
            throw new InvalidExperimentSpecException(validation);
        }
        String jobId = "JOB-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        ExperimentPlan plan = previewPlan(spec);
        ExperimentJob job = repository.save(new ExperimentJob(jobId, spec, plan));
        transitionAndPersist(job, ExperimentStatus.VALIDATED, "ExperimentSpec validated by Java");
        try {
            artifactRegistry.writeJson(jobId, ArtifactType.EXPERIMENT_SPEC, "experiment-spec.json", spec);
            artifactRegistry.writeJson(jobId, ArtifactType.EXPERIMENT_PLAN, "experiment-plan.json", plan);
            transitionAndPersist(job, ExperimentStatus.QUEUED, "Experiment queued for controlled runner");
            orchestrationExecutor.submit(() -> execute(job));
            return job;
        } catch (RuntimeException e) {
            fail(job, "Could not initialize experiment artifacts: " + e.getMessage());
            throw e;
        }
    }

    public ExperimentJob get(String jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Experiment job not found: " + jobId));
    }

    public List<ExperimentJob> list() { return repository.findAll(); }
    public ExperimentProgress progress(String jobId) { return get(jobId).getProgress(); }

    public ExperimentPlan previewPlan(ExperimentSpec spec) {
        ValidationResult validation = specValidator.validate(spec);
        if (!validation.valid()) throw new InvalidExperimentSpecException(validation);
        long totalRuns = (long) spec.codeLengths().size() * specValidator.calculateErrorRatePointCount(spec);
        return new ExperimentPlan("PLAN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(),
                spec, resolveTemplateVersion(spec), totalRuns,
                List.of("VALIDATE_SPEC", "RUN_EXPERIMENT", "VALIDATE_RESULT", "REGISTER_ARTIFACTS"), Instant.now());
    }

    /** Generic (declarative-template) plan: point count comes from the dynamic grid. */
    public ExperimentPlan previewPlan(GenericExperimentSpec spec) {
        ExperimentDefinition definition = definitionFor(spec);
        if (definition == null) {
            throw new InvalidExperimentSpecException(ValidationResult.failure(
                    List.of("No ACTIVE template is registered for experimentTypeId: "
                            + spec.experimentTypeId()), List.of()));
        }
        ExperimentGridResolver.Grid grid = gridResolver.resolve(definition, spec);
        return new ExperimentPlan("PLAN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(),
                spec, definition.templateId() + (spec.templateVersion() == null ? "" : "@" + spec.templateVersion()),
                grid.totalPoints(),
                List.of("VALIDATE_SPEC", "RUN_EXPERIMENT", "VALIDATE_RESULT", "REGISTER_ARTIFACTS"),
                Instant.now());
    }

    /** Create a declarative-template experiment job with real generic semantics. */
    public ExperimentJob create(GenericExperimentSpec spec) {
        ExperimentDefinition definition = definitionFor(spec);
        if (definition == null) {
            throw new InvalidExperimentSpecException(ValidationResult.failure(
                    List.of("No ACTIVE template is registered for experimentTypeId: "
                            + spec.experimentTypeId()), List.of()));
        }
        String jobId = "JOB-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        ExperimentPlan plan = previewPlan(spec);
        ExperimentJob job = repository.save(new ExperimentJob(jobId, spec, plan));
        transitionAndPersist(job, ExperimentStatus.VALIDATED, "Generic spec validated against template definition");
        try {
            artifactRegistry.writeJson(jobId, ArtifactType.EXPERIMENT_SPEC, "experiment-spec.json", spec);
            artifactRegistry.writeJson(jobId, ArtifactType.EXPERIMENT_PLAN, "experiment-plan.json", plan);
            transitionAndPersist(job, ExperimentStatus.QUEUED, "Experiment queued for controlled runner");
            orchestrationExecutor.submit(() -> execute(job));
            return job;
        } catch (RuntimeException e) {
            fail(job, "Could not initialize experiment artifacts: " + e.getMessage());
            throw e;
        }
    }

    private ExperimentDefinition definitionFor(GenericExperimentSpec spec) {
        if (definitionRegistry == null || spec.experimentTypeId() == null) return null;
        return definitionRegistry.byExperimentTypeId(spec.experimentTypeId()).orElse(null);
    }

    /**
     * Declarative specs resolve their plan template version to the ACTIVE templateId of the
     * registered experiment type; built-in specs keep the runner's fixed template version.
     */
    private String resolveTemplateVersion(ExperimentSpec spec) {
        if (spec.experimentTypeId() != null && templateRegistry != null) {
            return templateRegistry.byExperimentTypeId(spec.experimentTypeId())
                    .map(org.example.wavepilot.template.TemplateRecord::templateId)
                    .orElseThrow(() -> new IllegalStateException(
                            "No ACTIVE template is registered for experimentTypeId: "
                                    + spec.experimentTypeId()));
        }
        return runner.experimentTemplateVersion();
    }

    public List<ArtifactRecord> artifacts(String jobId) {
        get(jobId);
        return artifactRegistry.listByJobId(jobId);
    }

    public ExperimentSummaryView readExperimentSummary(String jobId) {
        ExperimentJob job = requireSucceeded(jobId);
        ArtifactRecord summary = artifactRegistry.listByJobId(jobId).stream()
                .filter(artifact -> artifact.type() == ArtifactType.SUMMARY_JSON)
                .findFirst().orElseThrow(() -> new IllegalStateException("Validated summary artifact is missing for " + jobId));
        try {
            Map<String, Object> values = objectMapper.readValue(Path.of(summary.path()).toFile(),
                    new TypeReference<Map<String, Object>>() { });
            boolean mock = Boolean.TRUE.equals(values.get("mock"));
            return new ExperimentSummaryView(job.getJobId(), mock, summary.artifactId(), summary.sha256(), values);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read validated summary for " + jobId, e);
        }
    }

    public ExperimentComparison compareExperiments(String firstJobId, String secondJobId) {
        ExperimentSummaryView first = readExperimentSummary(firstJobId);
        ExperimentSummaryView second = readExperimentSummary(secondJobId);
        double firstAverage = number(first.values().get("averageAccuracy"), "averageAccuracy", firstJobId);
        double secondAverage = number(second.values().get("averageAccuracy"), "averageAccuracy", secondJobId);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("firstAverageAccuracy", firstAverage);
        metrics.put("secondAverageAccuracy", secondAverage);
        metrics.put("difference", firstAverage - secondAverage);
        return new ExperimentComparison(firstJobId, secondJobId, first.mock() || second.mock(),
                first.artifactId(), second.artifactId(), metrics);
    }

    public ExperimentJob cancel(String jobId) {
        ExperimentJob job = get(jobId);
        synchronized (job) {
            if (job.getStatus().isTerminal()) {
                return job;
            }
            if (job.getExternalJobId() != null) {
                runner.cancel(job.getExternalJobId());
                registerAvailableRunnerArtifacts(job, job.getExternalJobId());
            }
            transitionAndPersist(job, ExperimentStatus.CANCELLED, "Experiment cancelled by user");
            return job;
        }
    }

    private ExperimentJob requireSucceeded(String jobId) {
        ExperimentJob job = get(jobId);
        if (job.getStatus() != ExperimentStatus.SUCCEEDED) {
            throw new IllegalStateException("Experiment must be SUCCEEDED before reading validated results: " + jobId);
        }
        return job;
    }

    private double number(Object value, String field, String jobId) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalStateException("Validated summary field " + field + " is unavailable for " + jobId);
        }
        return number.doubleValue();
    }

    private void execute(ExperimentJob job) {
        try {
            RunnerSubmission submission;
            synchronized (job) {
                if (job.getStatus().isTerminal()) {
                    return;
                }
                submission = runner.submit(job);
                job.setExternalJobId(submission.externalJobId());
            }
            while (!job.getStatus().isTerminal()) {
                RunnerStatus status = runner.getStatus(submission.externalJobId());
                if (status.state() == RunnerStatus.State.RUNNING && job.getStatus() == ExperimentStatus.QUEUED) {
                    transitionAndPersist(job, ExperimentStatus.RUNNING, "Controlled runner started");
                }
                if (status.state() == RunnerStatus.State.QUEUED || status.state() == RunnerStatus.State.RUNNING) {
                    job.updateProgress(status.progress(), status.state().name(), status.completedRuns(),
                            status.totalRuns(), status.message());
                    Thread.sleep(20);
                    continue;
                }
                if (status.state() == RunnerStatus.State.CANCELLED) {
                    registerAvailableRunnerArtifacts(job, submission.externalJobId());
                    if (!job.getStatus().isTerminal()) {
                        transitionAndPersist(job, ExperimentStatus.CANCELLED, status.message());
                    }
                    return;
                }
                if (status.state() == RunnerStatus.State.FAILED) {
                    registerAvailableRunnerArtifacts(job, submission.externalJobId());
                    fail(job, "Runner failed: " + status.message());
                    return;
                }
                if (job.getStatus() == ExperimentStatus.QUEUED) {
                    transitionAndPersist(job, ExperimentStatus.RUNNING, "Controlled runner completed quickly");
                }
                transitionAndPersist(job, ExperimentStatus.VALIDATING_RESULT,
                        "Validating files and structured experiment results");
                List<ProducedArtifact> produced = runner.collectArtifacts(submission.externalJobId());
                ValidationResult result = resultValidator.validate(job, status, produced);
                registerRunnerArtifacts(job, produced);
                if (!result.valid()) {
                    fail(job, String.join("; ", result.errors()));
                    return;
                }
                markArtifactsValidated(job);
                job.updateProgress(100, "VALIDATING_RESULT", status.totalRuns(), status.totalRuns(),
                        "Experiment results passed deterministic validation");
                transitionAndPersist(job, ExperimentStatus.SUCCEEDED,
                        runner.runnerType() + " experiment succeeded and artifacts were validated");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(job, "Experiment orchestration interrupted");
        } catch (Exception e) {
            fail(job, "Experiment orchestration failed: " + e.getMessage());
        }
    }

    private void registerAvailableRunnerArtifacts(ExperimentJob job, String externalJobId) {
        try {
            registerRunnerArtifacts(job, runner.collectArtifacts(externalJobId));
        } catch (RuntimeException ignored) {
            // Preserve the original runner failure/cancellation as the job outcome.
        }
    }

    private void registerRunnerArtifacts(ExperimentJob job, List<ProducedArtifact> produced) {
        if (produced == null) return;
        for (ProducedArtifact artifact : produced) {
            if (artifact == null) continue;
            Path candidate = artifact.path().toAbsolutePath().normalize();
            boolean alreadyRegistered = artifactRegistry.listByJobId(job.getJobId()).stream()
                    .anyMatch(existing -> existing.type() == artifact.type()
                            && Path.of(existing.path()).toAbsolutePath().normalize().equals(candidate));
            if (!alreadyRegistered) {
                artifactRegistry.register(job.getJobId(), artifact.type(), candidate);
            }
        }
    }

    private void markArtifactsValidated(ExperimentJob job) {
        Map<String, Object> summary = artifactRegistry.listByJobId(job.getJobId()).stream()
                .filter(record -> record.type() == ArtifactType.SUMMARY_JSON)
                .findFirst()
                .map(record -> {
                    try {
                        return objectMapper.readValue(artifactRegistry.resolveVerified(record.artifactId()).toFile(),
                                new TypeReference<Map<String, Object>>() { });
                    } catch (IOException e) {
                        throw new IllegalStateException("Could not read validated summary metadata", e);
                    }
                }).orElse(Map.of());
        boolean mock = Boolean.TRUE.equals(summary.get("mock"));
        boolean algorithmValidated = Boolean.TRUE.equals(summary.get("algorithmValidated"));
        String classification = String.valueOf(summary.getOrDefault("classification",
                mock ? "MOCK_RUNNER" : "UNCLASSIFIED"));
        String algorithmVersion = String.valueOf(summary.getOrDefault("algorithmVersion", "unknown"));
        artifactRegistry.markJobValidated(job.getJobId(), runner.runnerType(), mock,
                algorithmValidated, classification, job.getPlan().experimentTemplateVersion(), algorithmVersion);
    }

    /** 状态变更后立即写回仓储：file 仓储落盘快照，重启后可恢复历史状态。 */
    private void transitionAndPersist(ExperimentJob job, ExperimentStatus status, String message) {
        stateMachine.transition(job, status, message);
        repository.save(job);
    }

    private void fail(ExperimentJob job, String reason) {
        synchronized (job) {
            if (job.getStatus().isTerminal()) return;
            job.setFailureReason(reason);
            if (stateMachine.canTransition(job.getStatus(), ExperimentStatus.FAILED)) {
                transitionAndPersist(job, ExperimentStatus.FAILED, reason);
            }
        }
    }

    @PreDestroy
    public void shutdown() { orchestrationExecutor.shutdownNow(); }

    public static class InvalidExperimentSpecException extends RuntimeException {
        private final ValidationResult validationResult;

        public InvalidExperimentSpecException(ValidationResult validationResult) {
            super("Invalid ExperimentSpec: " + String.join("; ", validationResult.errors()));
            this.validationResult = validationResult;
        }

        public ValidationResult getValidationResult() { return validationResult; }
    }

    public record ExperimentSummaryView(String jobId, boolean mock, String artifactId,
                                        String sha256, Map<String, Object> values) { }

    public record ExperimentComparison(String firstJobId, String secondJobId, boolean mock,
                                       String firstArtifactId, String secondArtifactId,
                                       Map<String, Object> metrics) { }
}
