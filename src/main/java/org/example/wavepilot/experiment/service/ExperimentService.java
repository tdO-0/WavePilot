package org.example.wavepilot.experiment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.ExperimentGridResolver;
import org.example.wavepilot.experiment.lease.LeaseProperties;
import org.example.wavepilot.experiment.lease.LeaseMetrics;
import org.example.wavepilot.experiment.lease.WorkerIdentity;
import org.example.wavepilot.experiment.metrics.JobExecutionMetrics;
import org.example.wavepilot.experiment.outbox.OutboxDispatchService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.example.wavepilot.experiment.messaging.ExperimentMessagePublisher;

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
    private final JobExecutionMetrics jobMetrics;
    private final LeaseMetrics leaseMetrics;
    private final LeaseProperties leaseProperties;
    /** Jobs owned by this node (claim won, not yet terminal): heartbeat + execution timer. */
    private final ConcurrentMap<String, LeaseHolder> ownedLeases = new ConcurrentHashMap<>();
    private final ExecutorService orchestrationExecutor = Executors.newFixedThreadPool(4);
    private String nodeRole = "standalone";
    private ExperimentMessagePublisher publisher;
    private WorkerIdentity workerIdentity;
    // Present only with the MySQL backend: the transactional outbox replaces the
    // request-thread publish so 202 means "durably accepted", not "broker confirmed".
    private OutboxDispatchService outboxDispatch;

    @Autowired
    public void configureBackend(
            @org.springframework.beans.factory.annotation.Value("${wavepilot.node-role:standalone}") String role,
            org.springframework.beans.factory.ObjectProvider<ExperimentMessagePublisher> publishers,
            org.springframework.beans.factory.ObjectProvider<OutboxDispatchService> outboxDispatchers,
            org.springframework.beans.factory.ObjectProvider<WorkerIdentity> workerIdentities) {
        if (!List.of("standalone", "api", "worker").contains(role)) {
            throw new IllegalArgumentException("Unknown wavepilot.node-role: " + role);
        }
        this.nodeRole = role;
        this.publisher = publishers.getIfAvailable();
        this.outboxDispatch = outboxDispatchers.getIfAvailable();
        this.workerIdentity = workerIdentities.getIfAvailable();
        if (!"standalone".equals(role) && publisher == null) {
            throw new IllegalStateException("api/worker require the MySQL + RabbitMQ backend configuration");
        }
    }

    public ExperimentService(ExperimentSpecValidator specValidator, ExperimentStateMachine stateMachine,
                             ExperimentJobRepository repository, ExperimentRunner runner,
                             ArtifactRegistry artifactRegistry, ResultValidator resultValidator,
                             ObjectMapper objectMapper) {
        this(specValidator, stateMachine, repository, runner, artifactRegistry, resultValidator,
                objectMapper, null, null, new JobExecutionMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new LeaseMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new LeaseProperties(true, Duration.ofSeconds(60), 100, Duration.ofSeconds(5), List.of("mock")));
    }

    public ExperimentService(ExperimentSpecValidator specValidator, ExperimentStateMachine stateMachine,
                             ExperimentJobRepository repository, ExperimentRunner runner,
                             ArtifactRegistry artifactRegistry, ResultValidator resultValidator,
                             ObjectMapper objectMapper,
                             org.example.wavepilot.template.TemplateRegistry templateRegistry) {
        this(specValidator, stateMachine, repository, runner, artifactRegistry, resultValidator,
                objectMapper, templateRegistry, null, new JobExecutionMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new LeaseMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new LeaseProperties(true, Duration.ofSeconds(60), 100, Duration.ofSeconds(5), List.of("mock")));
    }

    /** Compatibility constructor for manual wiring in tests: isolated metrics + defaults. */
    public ExperimentService(ExperimentSpecValidator specValidator, ExperimentStateMachine stateMachine,
                             ExperimentJobRepository repository, ExperimentRunner runner,
                             ArtifactRegistry artifactRegistry, ResultValidator resultValidator,
                             ObjectMapper objectMapper,
                             org.example.wavepilot.template.TemplateRegistry templateRegistry,
                             org.example.wavepilot.template.definition.ExperimentDefinitionRegistry definitionRegistry) {
        this(specValidator, stateMachine, repository, runner, artifactRegistry, resultValidator, objectMapper,
                templateRegistry, definitionRegistry,
                new JobExecutionMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new LeaseMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new LeaseProperties(true, Duration.ofSeconds(60), 100, Duration.ofSeconds(5), List.of("mock")));
    }

    @Autowired
    public ExperimentService(ExperimentSpecValidator specValidator, ExperimentStateMachine stateMachine,
                             ExperimentJobRepository repository, ExperimentRunner runner,
                             ArtifactRegistry artifactRegistry, ResultValidator resultValidator,
                             ObjectMapper objectMapper,
                             org.example.wavepilot.template.TemplateRegistry templateRegistry,
                             org.example.wavepilot.template.definition.ExperimentDefinitionRegistry definitionRegistry,
                             JobExecutionMetrics jobMetrics, LeaseMetrics leaseMetrics,
                             LeaseProperties leaseProperties) {
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
        this.jobMetrics = jobMetrics;
        this.leaseMetrics = leaseMetrics;
        this.leaseProperties = leaseProperties;
    }

    public ValidationResult parseAndValidate(ExperimentSpec spec) {
        return specValidator.validate(spec);
    }

    public ExperimentJob create(ExperimentSpec spec) {
        return create(spec, null);
    }

    /**
     * Controlled idempotent entry point for durable AgentRun execution. The key never reaches
     * the Runner and cannot alter the ExperimentSpec; duplicate calls reuse the first job.
     */
    public ExperimentJob create(ExperimentSpec spec, String idempotencyKey) {
        requireSubmissionRole();
        if (idempotencyKey != null && !idempotencyKey.matches("[!-~]{1,128}"))
            throw new IllegalArgumentException("Idempotency-Key must contain 1-128 visible ASCII characters");
        if (idempotencyKey != null) {
            var existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return redispatchIfQueued(existing.get());
        }
        return createInternal(spec, idempotencyKey);
    }

    private ExperimentJob createInternal(ExperimentSpec spec, String key) {
        ValidationResult validation = specValidator.validate(spec);
        if (!validation.valid()) {
            throw new InvalidExperimentSpecException(validation);
        }
        String jobId = "JOB-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        ExperimentPlan plan = previewPlan(spec);
        ExperimentJob candidate = new ExperimentJob(jobId, spec, plan);
        candidate.setIdempotencyKey(key);
        ExperimentJob job = repository.insertIfAbsent(candidate);
        if (!job.getJobId().equals(jobId)) return redispatchIfQueued(job);
        jobMetrics.jobCreated(); // fresh insert: CREATED, version still 0
        transitionAndPersist(job, ExperimentStatus.VALIDATED, "ExperimentSpec validated by Java");
        try {
            artifactRegistry.writeJson(jobId, ArtifactType.EXPERIMENT_SPEC, "experiment-spec.json", spec);
            artifactRegistry.writeJson(jobId, ArtifactType.EXPERIMENT_PLAN, "experiment-plan.json", plan);
            transitionToQueued(job, "Experiment queued for controlled runner");
        } catch (RuntimeException e) {
            fail(job, "Could not initialize experiment artifacts: " + e.getMessage());
            throw e;
        }
        dispatch(job);
        return job;
    }

    private void requireSubmissionRole() {
        if ("worker".equals(nodeRole)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Worker does not accept experiment submissions");
    }

    private ExperimentJob redispatchIfQueued(ExperimentJob job) {
        // Allows the client to recover a failed dispatch with the same key; DB claim tolerates duplicates.
        if ("api".equals(nodeRole) && job.getStatus() == ExperimentStatus.QUEUED) {
            if (outboxDispatch != null) outboxDispatch.ensureQueuedEvent(job); // idempotent journal append
            else dispatch(job); // non-MySQL fallback: publish synchronously
        }
        return job;
    }

    /**
     * With the MySQL backend the api role commits the QUEUED transition and the outbox
     * event in one transaction and returns; the relay publishes asynchronously. The
     * synchronous publish below only survives for non-MySQL test/dev setups.
     */
    private void dispatch(ExperimentJob job) {
        if ("api".equals(nodeRole)) {
            if (outboxDispatch != null) return; // the relay owns publishing now
            try {
                publisher.publish(job.getJobId());
            } catch (RuntimeException e) {
                // No Outbox: keep the committed QUEUED record and report the job ID for recovery.
                throw new DispatchUnavailableException(job.getJobId(), e);
            }
        } else {
            orchestrationExecutor.submit(() -> execute(job));
        }
    }

    /** Returns false for a duplicate. The SQL claim is the only permission to run a Worker job. */
    public boolean executeQueued(String jobId) {
        ExperimentJob job = get(jobId);
        if (job.getStatus() == ExperimentStatus.CREATED || job.getStatus() == ExperimentStatus.VALIDATED)
            throw new org.springframework.dao.TransientDataAccessResourceException("Job is not queued yet");
        if (job.getStatus() != ExperimentStatus.QUEUED) return false;
        // Bind this node's identity and lease window BEFORE the conditional claim so the
        // SQL can persist them atomically; the winner's fencing token fends off zombies.
        Instant claimedAt = Instant.now();
        job.setWorkerId(workerId());
        job.refreshLease(claimedAt, leaseProperties.duration());
        if (!repository.tryClaim(job, job.getWorkerId())) {
            leaseMetrics.claimLost();
            return false;
        }
        leaseMetrics.claimWon();
        jobMetrics.recordQueueTime(Duration.between(job.getCreatedAt(), claimedAt));
        ownedLeases.put(jobId, new LeaseHolder(job, claimedAt));
        try {
            execute(job);
            if (job.getStatus() == ExperimentStatus.FAILED)
                throw new IllegalStateException(job.getFailureReason());
            return true;
        } catch (RuntimeException e) {
            // Never retry Runner submission after the claim: side effects may already exist.
            throw new IllegalStateException("Claimed execution failed; inspect job " + jobId, e);
        }
    }

    /** Stable node identity for claims; api never claims, its fallback id is cosmetic. */
    private String workerId() {
        if (workerIdentity != null) return workerIdentity.workerId();
        return "NODE-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
    }

    /** The node's currently owned jobs, for the heartbeat scheduler. */
    public java.util.Collection<ExperimentJob> activeLeasedJobs() {
        return ownedLeases.values().stream().map(LeaseHolder::job).toList();
    }

    public ExperimentJob get(String jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Experiment job not found: " + jobId));
    }

    public List<ExperimentJob> list() { return repository.findAll(); }
    public ExperimentProgress progress(String jobId) { return get(jobId).getProgress(); }

    public void attachSource(String jobId, String sourceJobId) {
        repository.attachSource(jobId, sourceJobId);
    }

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
        requireSubmissionRole();
        ExperimentDefinition definition = definitionFor(spec);
        if (definition == null) {
            throw new InvalidExperimentSpecException(ValidationResult.failure(
                    List.of("No ACTIVE template is registered for experimentTypeId: "
                            + spec.experimentTypeId()), List.of()));
        }
        String jobId = "JOB-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        ExperimentPlan plan = previewPlan(spec);
        ExperimentJob job = repository.insertIfAbsent(new ExperimentJob(jobId, spec, plan));
        jobMetrics.jobCreated(); // fresh insert: CREATED, version still 0
        transitionAndPersist(job, ExperimentStatus.VALIDATED, "Generic spec validated against template definition");
        try {
            artifactRegistry.writeJson(jobId, ArtifactType.EXPERIMENT_SPEC, "experiment-spec.json", spec);
            artifactRegistry.writeJson(jobId, ArtifactType.EXPERIMENT_PLAN, "experiment-plan.json", plan);
            transitionToQueued(job, "Experiment queued for controlled runner");
        } catch (RuntimeException e) {
            fail(job, "Could not initialize experiment artifacts: " + e.getMessage());
            throw e;
        }
        dispatch(job);
        return job;
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
            if (job.getExternalJobId() != null && !"api".equals(nodeRole)) {
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
                repository.save(job);
            }
            while (!job.getStatus().isTerminal()) {
                if (!"standalone".equals(nodeRole) && get(job.getJobId()).getStatus() == ExperimentStatus.CANCELLED) {
                    runner.cancel(submission.externalJobId());
                    return;
                }
                RunnerStatus status = runner.getStatus(submission.externalJobId());
                if (status.state() == RunnerStatus.State.RUNNING && job.getStatus() == ExperimentStatus.QUEUED) {
                    transitionAndPersist(job, ExperimentStatus.RUNNING, "Controlled runner started");
                }
                if (status.state() == RunnerStatus.State.QUEUED || status.state() == RunnerStatus.State.RUNNING) {
                    job.updateProgress(status.progress(), status.state().name(), status.completedRuns(),
                            status.totalRuns(), status.message());
                    if (job.getWorkerId() != null) job.refreshLease(Instant.now(), leaseProperties.duration());
                    repository.save(job);
                    Thread.sleep("standalone".equals(nodeRole) ? 20 : 200);
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
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            // A cancellation or a recovery transition won the database race; never overwrite
            // it with stale progress. Stop our Runner only when this node still owns the job.
            ExperimentJob current = get(job.getJobId());
            if (current.getStatus() == ExperimentStatus.CANCELLED) {
                if (job.getExternalJobId() != null) runner.cancel(job.getExternalJobId());
                return;
            }
            boolean lostOwnership = current.getStatus() == ExperimentStatus.QUEUED // re-queued by recovery
                    || current.getStatus() == ExperimentStatus.UNCERTAIN
                    || !Objects.equals(current.getWorkerId(), job.getWorkerId())
                    || current.getFencingToken() != job.getFencingToken();
            if (lostOwnership) {
                // Re-queued (side-effect-free runner only): stop the orphaned run.
                // UNCERTAIN: leave the external process alone — its outputs may still be
                // inspected by a human, and any write we make is fenced anyway.
                if (current.getStatus() == ExperimentStatus.QUEUED && job.getExternalJobId() != null) {
                    runner.cancel(job.getExternalJobId());
                }
                return;
            }
            throw e;
        } catch (org.springframework.dao.DataAccessException e) {
            // An in-memory terminal transition is not proof that its DB write succeeded.
            // Surface persistence failures to the consumer instead of ACKing a lost result.
            throw e;
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

    /**
     * 状态变更后立即写回仓储：file 仓储落盘快照，重启后可恢复历史状态。
     * The captured source status is pinned into the conditional UPDATE so a stale writer
     * can never overwrite a state that changed underneath it.
     */
    private void transitionAndPersist(ExperimentJob job, ExperimentStatus status, String message) {
        ExperimentStatus from = job.getStatus();
        stateMachine.transition(job, status, message);
        repository.save(job, from);
        jobMetrics.statusChanged(from, status);
        if (status.isTerminal()) recordExecutionTimeIfOwned(job.getJobId());
    }

    private void recordExecutionTimeIfOwned(String jobId) {
        LeaseHolder holder = ownedLeases.remove(jobId);
        if (holder != null) jobMetrics.recordExecutionTime(Duration.between(holder.claimStart(), Instant.now()));
    }

    /** Heartbeat and execution-timer state for a job this node won and still owns. */
    public record LeaseHolder(ExperimentJob job, Instant claimStart) { }

    /**
     * api + MySQL: the QUEUED transition and its outbox event must commit atomically, so
     * the request thread never depends on a broker confirm. All other setups keep the
     * plain transition (standalone executes in-process; non-MySQL api publishes directly).
     */
    private void transitionToQueued(ExperimentJob job, String message) {
        if ("api".equals(nodeRole) && outboxDispatch != null) {
            stateMachine.transition(job, ExperimentStatus.QUEUED, message);
            outboxDispatch.enqueueQueued(job, ExperimentStatus.VALIDATED);
        } else {
            transitionAndPersist(job, ExperimentStatus.QUEUED, message);
        }
    }

    private void fail(ExperimentJob job, String reason) {
        synchronized (job) {
            if (job.getStatus().isTerminal()) return;
            job.setFailureReason(reason);
            if (!stateMachine.canTransition(job.getStatus(), ExperimentStatus.FAILED)) return;
            try {
                transitionAndPersist(job, ExperimentStatus.FAILED, reason);
            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                // A previous write in this flow may have failed after mutating the in-memory
                // object (or been rolled back), so the pinned source status no longer matches
                // the row. Align with the database once and retry; the caller rethrows its
                // original error either way.
                ExperimentJob current = repository.findById(job.getJobId()).orElse(null);
                if (current != null && stateMachine.canTransition(current.getStatus(), ExperimentStatus.FAILED)) {
                    current.setFailureReason(reason);
                    transitionAndPersist(current, ExperimentStatus.FAILED, reason);
                }
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

    public static class DispatchUnavailableException extends RuntimeException {
        private final String jobId;
        public DispatchUnavailableException(String jobId, Throwable cause) {
            super("Job saved but message publish is unconfirmed", cause);
            this.jobId = jobId;
        }
        public String getJobId() { return jobId; }
    }

    public record ExperimentSummaryView(String jobId, boolean mock, String artifactId,
                                        String sha256, Map<String, Object> values) { }

    public record ExperimentComparison(String firstJobId, String secondJobId, boolean mock,
                                       String firstArtifactId, String secondArtifactId,
                                       Map<String, Object> metrics) { }
}
