package org.example.wavepilot.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.experiment.validation.ResultValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMatlabExperimentRunnerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void fixedCommandProducesAndValidatesCsvMatPngSummaryAndLog() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ExperimentSpecValidator specValidator = new ExperimentSpecValidator();
        ArtifactRegistry registry = new ArtifactRegistry(tempDirectory.toString(), mapper);
        AtomicReference<List<String>> command = new AtomicReference<>();
        AtomicReference<Path> workingDirectory = new AtomicReference<>();
        LocalMatlabExperimentRunner runner = new LocalMatlabExperimentRunner(
                registry, mapper, specValidator, properties(Duration.ofSeconds(2)), builder -> {
                    command.set(List.copyOf(builder.command()));
                    Path directory = builder.directory().toPath();
                    workingDirectory.set(directory);
                    writeSuccessfulArtifacts(directory, mapper);
                    return new CompletedProcess(0);
                });
        try {
            ExperimentJob job = job("JOB-MATLAB-UNIT", "'); system('unsafe-user-content'); %");
            RunnerSubmission submission = runner.submit(job);
            RunnerStatus status = waitUntilTerminal(runner, submission.externalJobId());

            assertEquals(RunnerStatus.State.SUCCEEDED, status.state());
            assertEquals(0, status.exitCode());
            assertEquals("fake-matlab", command.get().get(0));
            assertEquals("-batch", command.get().get(command.get().size() - 2));
            assertEquals(LocalMatlabExperimentRunner.MATLAB_ENTRYPOINT,
                    command.get().get(command.get().size() - 1));
            assertFalse(String.join(" ", command.get()).contains("unsafe-user-content"),
                    "Experiment description must stay in JSON and never enter the MATLAB command");
            assertTrue(Files.isRegularFile(workingDirectory.get()
                    .resolve("run_experiment.m")));
            assertTrue(Files.isRegularFile(workingDirectory.get()
                    .resolve("algorithm/estimate_k_binomial.m")));
            assertTrue(Files.isRegularFile(workingDirectory.get()
                    .resolve("TEMPLATE_MANIFEST.json")));
            assertTrue(Files.readString(workingDirectory.get()
                    .resolve(LocalMatlabExperimentRunner.INPUT_FILE)).contains("unsafe-user-content"));

            List<ProducedArtifact> artifacts = runner.collectArtifacts(submission.externalJobId());
            assertEquals(5, artifacts.size());
            assertTrue(artifacts.stream().anyMatch(a -> a.type() == ArtifactType.MAT_RESULT));
            assertTrue(artifacts.stream().anyMatch(a -> a.type() == ArtifactType.ACCURACY_CURVE));
            var validation = new ResultValidator(mapper, specValidator).validate(job, status, artifacts);
            assertTrue(validation.valid(), () -> String.join("; ", validation.errors()));
            assertTrue(Files.readString(workingDirectory.get().resolve("summary.json"))
                    .contains("\"mock\":false"));
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void terminatesProcessAndReportsTimeout() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        BlockingProcess process = new BlockingProcess();
        LocalMatlabExperimentRunner runner = new LocalMatlabExperimentRunner(
                new ArtifactRegistry(tempDirectory.toString(), mapper), mapper,
                new ExperimentSpecValidator(), properties(Duration.ofMillis(60)), builder -> process);
        try {
            RunnerSubmission submission = runner.submit(job("JOB-MATLAB-TIMEOUT", "timeout"));
            RunnerStatus status = waitUntilTerminal(runner, submission.externalJobId());

            assertEquals(RunnerStatus.State.FAILED, status.state());
            assertTrue(status.message().contains("timed out"));
            assertFalse(process.isAlive());
            assertTrue(runner.collectArtifacts(submission.externalJobId()).stream()
                    .anyMatch(artifact -> artifact.type() == ArtifactType.RUN_LOG));
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void cancellationTerminatesRunningProcessAndKeepsLog() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        BlockingProcess process = new BlockingProcess();
        CountDownLatch started = new CountDownLatch(1);
        LocalMatlabExperimentRunner runner = new LocalMatlabExperimentRunner(
                new ArtifactRegistry(tempDirectory.toString(), mapper), mapper,
                new ExperimentSpecValidator(), properties(Duration.ofSeconds(5)), builder -> {
                    started.countDown();
                    return process;
                });
        try {
            RunnerSubmission submission = runner.submit(job("JOB-MATLAB-CANCEL", "cancel"));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            runner.cancel(submission.externalJobId());
            RunnerStatus status = waitUntilTerminal(runner, submission.externalJobId());

            assertEquals(RunnerStatus.State.CANCELLED, status.state());
            assertFalse(process.isAlive());
            assertTrue(runner.collectArtifacts(submission.externalJobId()).stream()
                    .anyMatch(artifact -> artifact.type() == ArtifactType.RUN_LOG));
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void immediateCancellationStillExposesPreparedRunLog() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LocalMatlabExperimentRunner runner = new LocalMatlabExperimentRunner(
                new ArtifactRegistry(tempDirectory.toString(), mapper), mapper,
                new ExperimentSpecValidator(), properties(Duration.ofSeconds(5)),
                builder -> new BlockingProcess());
        try {
            RunnerSubmission submission = runner.submit(job("JOB-MATLAB-CANCEL-EARLY", "cancel early"));
            runner.cancel(submission.externalJobId());

            assertEquals(RunnerStatus.State.CANCELLED,
                    runner.getStatus(submission.externalJobId()).state());
            ProducedArtifact runLog = runner.collectArtifacts(submission.externalJobId()).stream()
                    .filter(artifact -> artifact.type() == ArtifactType.RUN_LOG)
                    .findFirst().orElseThrow();
            assertTrue(Files.readString(runLog.path()).contains("cancellation requested"));
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            runner.shutdown();
        }
    }

    private LocalMatlabRunnerProperties properties(Duration timeout) {
        LocalMatlabRunnerProperties properties = new LocalMatlabRunnerProperties();
        properties.setExecutable("fake-matlab");
        properties.setTimeout(timeout);
        properties.setPollInterval(Duration.ofMillis(10));
        properties.setShutdownGrace(Duration.ofMillis(20));
        return properties;
    }

    private ExperimentJob job(String jobId, String description) {
        ExperimentSpec spec = new ExperimentSpec(
                ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32, 64), 0.0, 0.02, 0.01,
                20, 10, 20L,
                List.of(OutputType.ACCURACY_CSV, OutputType.MAT_RESULT,
                        OutputType.ACCURACY_CURVE, OutputType.RUN_LOG),
                description);
        ExperimentPlan plan = new ExperimentPlan("PLAN-UNIT", spec,
                MatlabTemplateCatalog.SIMPLE_TEMPLATE, 6,
                List.of("RUN_EXPERIMENT", "VALIDATE_RESULT"), Instant.now());
        return new ExperimentJob(jobId, spec, plan);
    }

    private void writeSuccessfulArtifacts(Path directory, ObjectMapper mapper) throws IOException {
        Files.writeString(directory.resolve("accuracy.csv"),
                RealPolarCsv.sample());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("experimentType", "POLAR_CODE_K_IDENTIFICATION");
        summary.put("algorithmName", MatlabTemplateCatalog.SIMPLE_ALGORITHM_NAME);
        summary.put("algorithmVersion", MatlabTemplateCatalog.SIMPLE_ALGORITHM_VERSION);
        summary.put("templateVersion", MatlabTemplateCatalog.SIMPLE_TEMPLATE);
        summary.put("runnerType", "local-matlab");
        summary.put("mock", false);
        summary.put("algorithmValidated", false);
        summary.put("errorRateMeaning", "BSC_BIT_FLIP_PROBABILITY");
        summary.put("trueKRule", "15N/32");
        summary.put("randomSeed", 20);
        summary.put("totalPoints", 6);
        summary.put("completedPoints", 6);
        summary.put("minAccuracy", 0.70);
        summary.put("maxAccuracy", 0.90);
        summary.put("meanAccuracy", 0.80);
        summary.put("totalRuntimeSeconds", 0.1);
        summary.put("matlabVersion", "test-matlab");
        summary.put("success", true);
        mapper.writeValue(directory.resolve("summary.json").toFile(), summary);
        byte[] matHeader = new byte[512];
        byte[] matlabSignature = "MATLAB 5.0 MAT-file".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(matlabSignature, 0, matHeader, 0, matlabSignature.length);
        int offset = 128;
        for (String variable : List.of("accuracyMatrix", "estimatedKMatrix",
                "NVec", "errorVec", "trueKVec")) {
            byte[] token = variable.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(token, 0, matHeader, offset, token.length);
            offset += token.length + 4;
        }
        Files.write(directory.resolve("result.mat"), matHeader);
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0x00FFFFFF);
        ImageIO.write(image, "png", directory.resolve("accuracy-curve.png").toFile());
    }

    private static final class RealPolarCsv {
        private static String sample() {
            return "codeLength,trueK,errorRate,correctCount,monteCarloTimes,accuracy,sampleCount,"
                    + "randomSeed,meanEstimatedK,mae,bias,runtimeSeconds,algorithmVersion\n"
                    + "32,15,0,9,10,0.9,20,20,15,0,0,0.01,1.0.0\n"
                    + "32,15,0.01,8,10,0.8,20,20,15,0,0,0.01,1.0.0\n"
                    + "32,15,0.02,7,10,0.7,20,20,15,0,0,0.01,1.0.0\n"
                    + "64,30,0,9,10,0.9,20,20,30,0,0,0.01,1.0.0\n"
                    + "64,30,0.01,8,10,0.8,20,20,30,0,0,0.01,1.0.0\n"
                    + "64,30,0.02,7,10,0.7,20,20,30,0,0,0.01,1.0.0\n";
        }
    }

    private RunnerStatus waitUntilTerminal(LocalMatlabExperimentRunner runner,
                                           String externalJobId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(3);
        do {
            RunnerStatus status = runner.getStatus(externalJobId);
            if (status.terminal()) return status;
            Thread.sleep(10);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Local MATLAB runner did not become terminal");
    }

    private static class CompletedProcess extends Process {
        private final int exitCode;

        private CompletedProcess(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return false; }
    }

    private static final class BlockingProcess extends Process {
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private volatile boolean alive = true;

        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException {
            while (alive) Thread.sleep(5);
            return 143;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            Thread.sleep(Math.min(10, Math.max(1, unit.toMillis(timeout))));
            return !alive;
        }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException("still running");
            return 143;
        }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
