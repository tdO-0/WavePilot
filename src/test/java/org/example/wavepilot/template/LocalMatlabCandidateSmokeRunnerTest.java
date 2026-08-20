package org.example.wavepilot.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.smoke.CandidateSmokeRunner;
import org.example.wavepilot.template.smoke.LocalMatlabCandidateSmokeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests of the real-MATLAB smoke runner with an injected fake process launcher. */
class LocalMatlabCandidateSmokeRunnerTest {

    @TempDir Path root;

    private TemplateCandidate candidate() throws Exception {
        ExperimentDefinitionRegistry definitions = DeclarativeTestSupport.registryWithDemo();
        // A demo candidate whose MATLAB script really produces the declared artifacts.
        String script = "function run_experiment(inputFile, outputDir)\n"
                + "  p = jsondecode(fileread(inputFile)).customParameters;\n"
                + "  fid = fopen(fullfile(outputDir, 'accuracy.csv'), 'w');\n"
                + "  fprintf(fid, 'ebNo,berSim,berTheory\\n0,0.1,0.09\\n1,0.2,0.21\\n');\n"
                + "  fclose(fid);\n"
                + "  s = struct('experimentType','demo-ber-awgn','algorithmName','demo','rowCount',2);\n"
                + "  fid = fopen(fullfile(outputDir, 'summary.json'), 'w');\n"
                + "  fprintf(fid, '%s', jsonencode(s));\n"
                + "  fclose(fid);\n"
                + "  fid = fopen(fullfile(outputDir, 'accuracy-curve.png'), 'w');\n"
                + "  fclose(fid);\n"
                + "  fid = fopen(fullfile(outputDir, 'run.log'), 'w');\n"
                + "  fprintf(fid, 'smoke ok\\n');\n"
                + "  fclose(fid);\n"
                + "end\n";
        return new TemplateCandidate("CAND-SMOKE-1", "demo-ber-awgn", "demo-ber-awgn",
                "演示模板", "1.0.0", org.example.wavepilot.template.candidate.TemplateCandidateStatus.SMOKE_PENDING,
                TemplateSource.AGENT_GENERATED, "smoke test", DeclarativeTestSupport.DEMO_DEFINITION_YAML,
                "{\"templateName\":\"demo-ber-awgn\"}", "notes", List.of(), List.of(),
                List.of(new TemplateCandidate.CandidateFile("matlab/run_experiment.m", script, "h")),
                List.of(), null, false, null,
                java.time.Instant.now(), java.time.Instant.now());
    }

    @Test
    void successfulMatlabProcessWithValidArtifactsPassesSmoke() throws Exception {
        FakeLauncher launcher = new FakeLauncher(0, true);
        LocalMatlabCandidateSmokeRunner runner = new LocalMatlabCandidateSmokeRunner(
                new org.example.wavepilot.template.definition.ExperimentDefinitionParser(),
                new ObjectMapper().findAndRegisterModules(),
                "matlab", Duration.ofSeconds(30), launcher);

        CandidateSmokeRunner.SmokeResult result = runner.run(candidate());
        assertTrue(result.executed());
        assertTrue(result.passed(), "valid artifacts must pass: " + result.report());
        assertTrue(result.report().contains("Smoke 通过"));
        // Only the candidate files were copied; nothing outside the work dir was touched.
        assertTrue(launcher.command().get(0).equals("matlab"));
    }

    @Test
    void failedMatlabProcessFailsSmokeWithOutput() throws Exception {
        FakeLauncher launcher = new FakeLauncher(1, true);
        LocalMatlabCandidateSmokeRunner runner = new LocalMatlabCandidateSmokeRunner(
                new org.example.wavepilot.template.definition.ExperimentDefinitionParser(),
                new ObjectMapper().findAndRegisterModules(),
                "matlab", Duration.ofSeconds(30), launcher);

        CandidateSmokeRunner.SmokeResult result = runner.run(candidate());
        assertTrue(result.executed());
        assertFalse(result.passed());
        assertTrue(result.report().contains("exit 1"));
    }

    @Test
    void artifactsFailingTheDeclaredContractFailSmoke() throws Exception {
        // Script produces no summary.json -> contract validation fails.
        String badScript = "function run_experiment(inputFile, outputDir)\n"
                + "  p = jsondecode(fileread(inputFile)).customParameters;\n"
                + "  fid = fopen(fullfile(outputDir, 'accuracy.csv'), 'w');\n"
                + "  fprintf(fid, 'ebNo,berSim,berTheory\\n0,0.1,0.09\\n');\n"
                + "  fclose(fid);\n"
                + "end\n";
        TemplateCandidate bad = new TemplateCandidate(candidate().candidateId(), candidate().templateId(),
                candidate().experimentTypeId(), candidate().displayName(), candidate().version(),
                candidate().status(), candidate().source(), candidate().request(),
                candidate().definitionYaml(), candidate().manifestJson(), candidate().generationNotes(),
                candidate().assumptions(), candidate().unresolvedQuestions(),
                List.of(new TemplateCandidate.CandidateFile("matlab/run_experiment.m", badScript, "h")),
                candidate().securityFindings(), candidate().smokeReport(), candidate().realSmokeExecuted(),
                candidate().failureReason(), candidate().createdAt(), candidate().updatedAt());
        FakeLauncher launcher = new FakeLauncher(0, false, true);
        LocalMatlabCandidateSmokeRunner runner = new LocalMatlabCandidateSmokeRunner(
                new org.example.wavepilot.template.definition.ExperimentDefinitionParser(),
                new ObjectMapper().findAndRegisterModules(),
                "matlab", Duration.ofSeconds(30), launcher);

        CandidateSmokeRunner.SmokeResult result = runner.run(bad);
        assertTrue(result.executed());
        assertFalse(result.passed());
        assertTrue(result.report().contains("契约"));
    }

    @Test
    void timedOutProcessIsForciblyKilledAndReported() throws Exception {
        FakeLauncher launcher = new FakeLauncher(0, false, false);
        LocalMatlabCandidateSmokeRunner runner = new LocalMatlabCandidateSmokeRunner(
                new org.example.wavepilot.template.definition.ExperimentDefinitionParser(),
                new ObjectMapper().findAndRegisterModules(),
                "matlab", Duration.ofMillis(50), launcher);

        CandidateSmokeRunner.SmokeResult result = runner.run(candidate());
        assertTrue(result.executed());
        assertFalse(result.passed());
        assertTrue(result.report().contains("超时"));
        assertTrue(launcher.destroyed(), "timed out processes must be forcibly destroyed");
    }

    @Test
    void unsafeCandidatePathsAreRejectedBeforeAnyProcessStarts() throws Exception {
        TemplateCandidate evil = new TemplateCandidate("CAND-EVIL", "demo-ber-awgn", "demo-ber-awgn",
                "x", "1.0.0", org.example.wavepilot.template.candidate.TemplateCandidateStatus.SMOKE_PENDING,
                TemplateSource.AGENT_GENERATED, "x", DeclarativeTestSupport.DEMO_DEFINITION_YAML, "{}",
                "n", List.of(), List.of(),
                List.of(new TemplateCandidate.CandidateFile("../escape.m", "x", "h")),
                List.of(), null, false, null, java.time.Instant.now(), java.time.Instant.now());
        FakeLauncher launcher = new FakeLauncher(0, true);
        LocalMatlabCandidateSmokeRunner runner = new LocalMatlabCandidateSmokeRunner(
                new org.example.wavepilot.template.definition.ExperimentDefinitionParser(),
                new ObjectMapper().findAndRegisterModules(),
                "matlab", Duration.ofSeconds(30), launcher);

        CandidateSmokeRunner.SmokeResult result = runner.run(evil);
        assertFalse(result.passed());
        assertTrue(result.report().contains("不安全路径"));
        assertFalse(launcher.started(), "unsafe paths must never reach a process");
    }

    /** Fake launcher: succeeds or fails on demand, optionally writing the artifacts itself. */
    private static final class FakeLauncher implements LocalMatlabCandidateSmokeRunner.ProcessLauncher {

        private final int exitCode;
        private final boolean writeArtifacts;
        private final AtomicReference<ProcessBuilder> builder = new AtomicReference<>();
        private final AtomicReference<Process> process = new AtomicReference<>();
        private final boolean finished;
        private volatile boolean destroyed;

        FakeLauncher(int exitCode, boolean writeArtifacts) {
            this(exitCode, writeArtifacts, true);
        }

        FakeLauncher(int exitCode, boolean writeArtifacts, boolean finished) {
            this.exitCode = exitCode;
            this.writeArtifacts = writeArtifacts;
            this.finished = finished;
        }

        @Override
        public Process start(ProcessBuilder processBuilder) throws IOException {
            builder.set(processBuilder);
            Path workDir = processBuilder.directory().toPath();
            if (writeArtifacts) {
                Path csv = workDir.resolve("accuracy.csv");
                Files.writeString(csv, "ebNo,berSim,berTheory\n0,0.1,0.09\n1,0.2,0.21\n",
                        StandardCharsets.UTF_8);
                Files.writeString(workDir.resolve("summary.json"),
                        "{\"experimentType\":\"demo-ber-awgn\",\"algorithmName\":\"demo\",\"rowCount\":2}",
                        StandardCharsets.UTF_8);
                Files.write(workDir.resolve("accuracy-curve.png"), new byte[]{1, 2, 3});
                Files.writeString(workDir.resolve("run.log"), "smoke ok\n", StandardCharsets.UTF_8);
            }
            Process fake = new Process() {
                @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
                @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
                @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
                @Override public int waitFor() { return exitCode; }
                @Override public boolean waitFor(long timeout, TimeUnit unit) {
                    if (!writeArtifacts && !finished) {
                        // Simulate a process that never finishes within the timeout.
                        try { Thread.sleep(unit.toMillis(timeout) + 200); } catch (InterruptedException ignored) { }
                        return false;
                    }
                    return true;
                }
                @Override public int exitValue() { return exitCode; }
                @Override public void destroy() { destroyed = true; }
                @Override public Process destroyForcibly() { destroyed = true; return this; }
                @Override public boolean isAlive() { return false; }
            };
            process.set(fake);
            return fake;
        }

        List<String> command() { return builder.get() == null ? List.of() : builder.get().command(); }
        boolean started() { return builder.get() != null; }
        boolean destroyed() { return destroyed; }
    }
}
