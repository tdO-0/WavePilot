package org.example.wavepilot.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "wavepilot.runner.type", havingValue = "local-matlab")
public class LocalMatlabExperimentRunner implements ExperimentRunner {

    static final String INPUT_FILE = "matlab-input.json";
    static final String PROGRESS_FILE = "matlab-progress.json";
    static final String MATLAB_ENTRYPOINT = MatlabTemplateCatalog.MATLAB_ENTRYPOINT;

    private final ArtifactRegistry artifactRegistry;
    private final ObjectMapper objectMapper;
    private final ExperimentSpecValidator specValidator;
    private final LocalMatlabRunnerProperties properties;
    private final MatlabTemplateCatalog.MatlabTemplate template;
    private final ProcessLauncher processLauncher;
    private final org.example.wavepilot.template.FileSystemTemplateRepository fileRepository;
    private final org.example.wavepilot.template.TemplateRegistry templateRegistry;
    private final ExecutorService executor;
    private final ConcurrentMap<String, MatlabExecution> executions = new ConcurrentHashMap<>();

    public LocalMatlabExperimentRunner(ArtifactRegistry artifactRegistry, ObjectMapper objectMapper,
                                       ExperimentSpecValidator specValidator,
                                       LocalMatlabRunnerProperties properties) {
        this(artifactRegistry, objectMapper, specValidator, properties, ProcessBuilder::start,
                null, null);
    }

    @Autowired
    public LocalMatlabExperimentRunner(ArtifactRegistry artifactRegistry, ObjectMapper objectMapper,
                                       ExperimentSpecValidator specValidator,
                                       LocalMatlabRunnerProperties properties,
                                       org.example.wavepilot.template.FileSystemTemplateRepository fileRepository,
                                       org.example.wavepilot.template.TemplateRegistry templateRegistry) {
        this(artifactRegistry, objectMapper, specValidator, properties, ProcessBuilder::start,
                fileRepository, templateRegistry);
    }

    LocalMatlabExperimentRunner(ArtifactRegistry artifactRegistry, ObjectMapper objectMapper,
                                ExperimentSpecValidator specValidator,
                                LocalMatlabRunnerProperties properties,
                                ProcessLauncher processLauncher) {
        this(artifactRegistry, objectMapper, specValidator, properties, processLauncher, null, null);
    }

    LocalMatlabExperimentRunner(ArtifactRegistry artifactRegistry, ObjectMapper objectMapper,
                                ExperimentSpecValidator specValidator,
                                LocalMatlabRunnerProperties properties,
                                ProcessLauncher processLauncher,
                                org.example.wavepilot.template.FileSystemTemplateRepository fileRepository,
                                org.example.wavepilot.template.TemplateRegistry templateRegistry) {
        this.artifactRegistry = Objects.requireNonNull(artifactRegistry);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.specValidator = Objects.requireNonNull(specValidator);
        this.properties = validateProperties(properties);
        this.template = MatlabTemplateCatalog.require(properties.getTemplate());
        this.processLauncher = Objects.requireNonNull(processLauncher);
        this.fileRepository = fileRepository;
        this.templateRegistry = templateRegistry;
        this.executor = Executors.newFixedThreadPool(2);
    }

    @Override
    public RunnerSubmission submit(ExperimentJob job) {
        Objects.requireNonNull(job, "Experiment job is required");
        ValidationResult validation = specValidator.validate(job.getSpec());
        if (!validation.valid()) {
            throw new IllegalArgumentException("Local MATLAB runner rejected invalid ExperimentSpec: "
                    + String.join("; ", validation.errors()));
        }
        if (job.getPlan() == null) {
            throw new IllegalArgumentException("Experiment plan is required");
        }

        String externalJobId = "MATLAB-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        MatlabExecution execution = new MatlabExecution(externalJobId, job.getPlan().totalRuns());
        Path jobDirectory = artifactRegistry.createJobDirectory(job.getJobId());
        execution.jobDirectory = jobDirectory;
        execution.logFile = jobDirectory.resolve("run.log");
        try {
            prepareJobFiles(job, jobDirectory, execution.logFile);
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare the fixed MATLAB job files", e);
        }
        executions.put(externalJobId, execution);
        execution.future = executor.submit(() -> execute(jobDirectory, execution));
        return new RunnerSubmission(externalJobId, Instant.now());
    }

    @Override
    public RunnerStatus getStatus(String externalJobId) {
        return execution(externalJobId).status;
    }

    @Override
    public void cancel(String externalJobId) {
        MatlabExecution execution = execution(externalJobId);
        execution.cancelled.set(true);
        terminateProcessTree(execution.process, properties.getShutdownGrace());
        Future<?> future = execution.future;
        if (future != null) {
            future.cancel(true);
        }
        if (!execution.status.terminal()) {
            execution.status = status(execution, RunnerStatus.State.CANCELLED,
                    execution.status.progress(), execution.status.completedRuns(),
                    "Local MATLAB process cancelled", null);
        }
        appendLog(execution.logFile, "Java runner cancellation requested.\n");
    }

    @Override
    public List<ProducedArtifact> collectArtifacts(String externalJobId) {
        MatlabExecution execution = execution(externalJobId);
        collectExistingArtifacts(execution.jobDirectory, execution);
        return List.copyOf(execution.artifacts);
    }

    @Override
    public String runnerType() {
        return "local-matlab";
    }

    @Override
    public String experimentTemplateVersion() {
        return template.version();
    }

    private void execute(Path jobDirectory, MatlabExecution execution) {
        try {
            checkCancelled(execution);

            ProcessBuilder processBuilder = matlabProcess(jobDirectory, execution.logFile);
            execution.command = List.copyOf(processBuilder.command());
            execution.process = processLauncher.start(processBuilder);
            checkCancelled(execution);
            execution.status = status(execution, RunnerStatus.State.RUNNING, 0, 0,
                    "Local MATLAB process started", null);

            monitor(jobDirectory, execution);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminateProcessTree(execution.process, properties.getShutdownGrace());
            if (execution.cancelled.get()) {
                execution.status = status(execution, RunnerStatus.State.CANCELLED,
                        execution.status.progress(), execution.status.completedRuns(),
                        "Local MATLAB process cancelled", null);
            } else {
                execution.status = status(execution, RunnerStatus.State.FAILED,
                        execution.status.progress(), execution.status.completedRuns(),
                        "Local MATLAB runner interrupted", -1);
            }
        } catch (Exception e) {
            terminateProcessTree(execution.process, properties.getShutdownGrace());
            appendLog(execution.logFile, "Java runner failure: " + safeMessage(e) + "\n");
            execution.status = status(execution, RunnerStatus.State.FAILED,
                    execution.status.progress(), execution.status.completedRuns(),
                    safeMessage(e), 1);
        } finally {
            collectExistingArtifacts(jobDirectory, execution);
        }
    }

    private void prepareJobFiles(ExperimentJob job, Path jobDirectory, Path logFile) throws IOException {
        String templateVersion = job.getPlan().experimentTemplateVersion();
        try {
            // Classpath built-in template (cataloged): existing behavior, fully unchanged.
            MatlabTemplateCatalog.MatlabTemplate cataloged = MatlabTemplateCatalog.require(templateVersion);
            for (String relativeFile : cataloged.resourceFiles()) {
                String resource = cataloged.resourceRoot() + "/" + relativeFile;
                Path destination = jobDirectory.resolve(relativeFile).normalize();
                if (!destination.startsWith(jobDirectory)) {
                    throw new IOException("Fixed MATLAB template path escaped the job directory: " + relativeFile);
                }
                Files.createDirectories(destination.getParent());
                try (InputStream input = LocalMatlabExperimentRunner.class.getResourceAsStream(resource)) {
                    if (input == null) {
                        throw new IOException("Fixed MATLAB template resource is missing: " + resource);
                    }
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (java.util.NoSuchElementException e) {
            // Declarative template: files come from the approved filesystem directory of the
            // ACTIVE version. Every file there passed security scanning and explicit approval.
            copyApprovedTemplateFiles(templateVersion, jobDirectory);
        }
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(jobDirectory.resolve(INPUT_FILE).toFile(), job.getSpec());
        Files.deleteIfExists(jobDirectory.resolve(PROGRESS_FILE));
        Files.writeString(logFile,
                "WavePilot LocalMatlabExperimentRunner\n"
                        + "templateVersion=" + templateVersion + "\n"
                        + "The command is fixed; ExperimentSpec is passed only through matlab-input.json.\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void copyApprovedTemplateFiles(String templateVersion, Path jobDirectory) throws IOException {
        if (fileRepository == null || templateRegistry == null) {
            throw new IOException("Unsupported MATLAB template (no filesystem template source): "
                    + templateVersion);
        }
        org.example.wavepilot.template.TemplateRecord active = templateRegistry.active(templateVersion)
                .orElseThrow(() -> new IOException(
                        "No ACTIVE version is registered for template: " + templateVersion));
        Path approved = fileRepository.approvedDirectory(templateVersion, active.version());
        if (!Files.isDirectory(approved)) {
            throw new IOException("Approved template directory is missing: " + approved);
        }
        try (var walk = Files.walk(approved)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relativePath = approved.relativize(file).toString().replace('\\', '/');
                // Approved packages keep MATLAB sources under matlab/; the fixed entry point
                // must land in the job root so `-batch run_experiment(...)` resolves it.
                if (relativePath.startsWith("matlab/")) {
                    relativePath = relativePath.substring("matlab/".length());
                }
                Path destination = jobDirectory.resolve(relativePath).normalize();
                if (!destination.startsWith(jobDirectory)) {
                    throw new IOException("Approved template file escaped the job directory: " + relativePath);
                }
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private ProcessBuilder matlabProcess(Path jobDirectory, Path logFile) {
        ProcessBuilder builder = new ProcessBuilder(
                properties.getExecutable(),
                "-sd", jobDirectory.toString(),
                "-batch", MATLAB_ENTRYPOINT);
        builder.directory(jobDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        return builder;
    }

    private void monitor(Path jobDirectory, MatlabExecution execution) throws Exception {
        Process process = execution.process;
        Instant deadline = Instant.now().plus(properties.getTimeout());
        long pollMillis = properties.getPollInterval().toMillis();

        while (process.isAlive()) {
            checkCancelled(execution);
            refreshProgress(jobDirectory.resolve(PROGRESS_FILE), execution);
            if (!Instant.now().isBefore(deadline)) {
                terminateProcessTree(process, properties.getShutdownGrace());
                appendLog(execution.logFile,
                        "Java runner timeout after " + properties.getTimeout() + ".\n");
                execution.status = status(execution, RunnerStatus.State.FAILED,
                        execution.status.progress(), execution.status.completedRuns(),
                        "Local MATLAB process timed out after " + properties.getTimeout(), -1);
                return;
            }
            process.waitFor(pollMillis, TimeUnit.MILLISECONDS);
        }

        refreshProgress(jobDirectory.resolve(PROGRESS_FILE), execution);
        int exitCode = process.exitValue();
        if (execution.cancelled.get()) {
            execution.status = status(execution, RunnerStatus.State.CANCELLED,
                    execution.status.progress(), execution.status.completedRuns(),
                    "Local MATLAB process cancelled", exitCode);
        } else if (exitCode != 0) {
            execution.status = status(execution, RunnerStatus.State.FAILED,
                    execution.status.progress(), execution.status.completedRuns(),
                    "MATLAB exited with code " + exitCode + "; inspect run.log", exitCode);
        } else {
            execution.status = status(execution, RunnerStatus.State.SUCCEEDED, 100,
                    execution.totalRuns, "Local MATLAB experiment completed", 0);
        }
    }

    private void refreshProgress(Path progressFile, MatlabExecution execution) {
        if (!Files.isRegularFile(progressFile)) {
            return;
        }
        try {
            MatlabProgress progress = objectMapper.readValue(progressFile.toFile(), MatlabProgress.class);
            long completed = Math.max(0, Math.min(execution.totalRuns, progress.completedRuns()));
            int percentage = Math.max(0, Math.min(99, progress.progress()));
            String message = progress.message() == null || progress.message().isBlank()
                    ? "MATLAB progress updated" : progress.message();
            execution.status = status(execution, RunnerStatus.State.RUNNING, percentage, completed,
                    message, null);
        } catch (IOException ignored) {
            // MATLAB may be replacing the progress file while it is being polled.
        }
    }

    private void collectExistingArtifacts(Path jobDirectory, MatlabExecution execution) {
        if (jobDirectory == null) {
            return;
        }
        synchronized (execution.artifacts) {
            execution.artifacts.clear();
            addIfRegular(execution.artifacts, ArtifactType.ACCURACY_CSV,
                    jobDirectory.resolve("accuracy.csv"));
            addIfRegular(execution.artifacts, ArtifactType.MAT_RESULT,
                    jobDirectory.resolve("result.mat"));
            addIfRegular(execution.artifacts, ArtifactType.ACCURACY_CURVE,
                    jobDirectory.resolve("accuracy-curve.png"));
            addIfRegular(execution.artifacts, ArtifactType.SUMMARY_JSON,
                    jobDirectory.resolve("summary.json"));
            addIfRegular(execution.artifacts, ArtifactType.RUN_LOG,
                    jobDirectory.resolve("run.log"));
        }
    }

    private void addIfRegular(List<ProducedArtifact> artifacts, ArtifactType type, Path path) {
        if (Files.isRegularFile(path)) {
            artifacts.add(new ProducedArtifact(type, path));
        }
    }

    private void checkCancelled(MatlabExecution execution) throws InterruptedException {
        if (execution.cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Local MATLAB run cancelled");
        }
    }

    private void terminateProcessTree(Process process, Duration grace) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            List<ProcessHandle> descendants = new ArrayList<>(process.toHandle().descendants().toList());
            Collections.reverse(descendants);
            descendants.forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS);
            }
            descendants.forEach(handle -> {
                if (handle.isAlive()) handle.destroyForcibly();
            });
        } catch (UnsupportedOperationException ignored) {
            process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void appendLog(Path logFile, String message) {
        if (logFile == null) return;
        try {
            Files.writeString(logFile, message, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Preserve the runner state even if diagnostic logging fails.
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replace('\r', ' ').replace('\n', ' ').substring(0, Math.min(500, message.length()));
    }

    private RunnerStatus status(MatlabExecution execution, RunnerStatus.State state, int progress,
                                long completedRuns, String message, Integer exitCode) {
        return new RunnerStatus(execution.externalJobId, state, progress, completedRuns,
                execution.totalRuns, message, exitCode, Instant.now());
    }

    private MatlabExecution execution(String externalJobId) {
        MatlabExecution execution = executions.get(externalJobId);
        if (execution == null) {
            throw new NoSuchElementException("Runner job not found: " + externalJobId);
        }
        return execution;
    }

    private LocalMatlabRunnerProperties validateProperties(LocalMatlabRunnerProperties value) {
        Objects.requireNonNull(value, "Local MATLAB properties are required");
        String executable = value.getExecutable();
        if (executable == null || executable.isBlank()
                || executable.indexOf('\0') >= 0 || executable.contains("\r") || executable.contains("\n")) {
            throw new IllegalArgumentException("A safe MATLAB executable is required");
        }
        MatlabTemplateCatalog.require(value.getTemplate());
        requirePositive(value.getTimeout(), "timeout");
        requirePositive(value.getPollInterval(), "pollInterval");
        requirePositive(value.getShutdownGrace(), "shutdownGrace");
        return value;
    }

    private void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Local MATLAB " + name + " must be positive");
        }
    }

    @PreDestroy
    public void shutdown() {
        executions.values().forEach(execution -> {
            if (!execution.status.terminal()) {
                cancel(execution.externalJobId);
            }
        });
        executor.shutdownNow();
    }

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }

    private record MatlabProgress(int progress, long completedRuns, long totalRuns, String message) { }

    private static final class MatlabExecution {
        private final String externalJobId;
        private final long totalRuns;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<ProducedArtifact> artifacts = Collections.synchronizedList(new ArrayList<>());
        private volatile RunnerStatus status;
        private volatile Future<?> future;
        private volatile Process process;
        private volatile Path jobDirectory;
        private volatile Path logFile;
        private volatile List<String> command = List.of();

        private MatlabExecution(String externalJobId, long totalRuns) {
            this.externalJobId = externalJobId;
            this.totalRuns = totalRuns;
            this.status = new RunnerStatus(externalJobId, RunnerStatus.State.QUEUED,
                    0, 0, totalRuns, "Local MATLAB run queued", null, Instant.now());
        }
    }
}
