package org.example.wavepilot;

import org.example.wavepilot.agent.WavePilotAgentTools;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.report.ReportLanguageModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The platform security boundaries must survive the full integration chain. */
class SecurityBoundaryIntegrationTest {

    @TempDir Path root;

    @Test
    void reportModelBoundaryExposesNoFilesystemOrProcessHandles() {
        for (Method method : ReportLanguageModel.class.getDeclaredMethods()) {
            assertFalse(method.getReturnType().getName().contains("java.nio.file"),
                    "report model must not return file handles");
            // The interface is fixed: structured report data plus the template Markdown, nothing else.
            assertEquals(2, method.getParameterCount(),
                    "report model receives only ExperimentReportData and template Markdown");
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter.getName().contains("Path") || parameter.getName().contains("File"),
                        "report model must not receive filesystem types");
                assertFalse(parameter.getName().contains("ExperimentJob"),
                        "report model must not receive jobs");
                assertTrue(parameter.getName().contains("ExperimentReportData")
                                || parameter.getName().equals(String.class.getName()),
                        "report model receives report data and markdown only");
            }
        }
    }

    @Test
    void agentToolsNeverExposeRunnerRepositoryOrProcessBuilder() {
        Class<?> tools = WavePilotAgentTools.class;
        String source = tools.getName();
        for (String forbidden : List.of("ProcessBuilder", "ExperimentJobRepository", "ExperimentRunner",
                "Path", "FileWriter", "Runtime.getRuntime")) {
            boolean leaked = Arrays.stream(tools.getDeclaredFields())
                    .anyMatch(field -> field.getType().getName().contains(forbidden));
            assertFalse(leaked, source + " must not hold a " + forbidden);
        }
    }

    @Test
    void artifactRegistryRejectsDirectoryEscapesAndUnsafeJobIds() throws Exception {
        IntegrationTestSupport.Stack stack = IntegrationTestSupport.stack(root);
        ArtifactRegistry registry = stack.registry();

        Path outside = Files.createTempFile("outside", ".txt");
        Files.writeString(outside, "trespass");
        assertThrows(ArtifactRegistry.ArtifactStorageException.class,
                () -> registry.register("JOB-OK", org.example.wavepilot.artifact.ArtifactType.RUN_LOG, outside),
                "registering a file outside the job directory must be rejected");
        assertThrows(ArtifactRegistry.ArtifactStorageException.class,
                () -> registry.createJobDirectory("../escape"));
        assertThrows(ArtifactRegistry.ArtifactStorageException.class,
                () -> registry.createJobDirectory("JOB WITH SPACES!"));
    }

    @Test
    void replayRejectsTamperedSourceArtifactsThroughTheWholeChain() throws Exception {
        IntegrationTestSupport.Stack stack = IntegrationTestSupport.stack(root);
        var job = stack.experimentService().create(WavePilotTestFixtures.validSpec());
        stack.awaitJob(job.getJobId());
        var csv = stack.registry().listByJobId(job.getJobId()).stream()
                .filter(record -> record.type() == org.example.wavepilot.artifact.ArtifactType.ACCURACY_CSV)
                .findFirst().orElseThrow();
        Path verified = stack.registry().resolveVerified(csv.artifactId());
        Files.writeString(verified, "32,15,0,1,10,0.1,50,20,15,0,0,0.01,1.0.0\n");

        org.example.wavepilot.replay.ReplayService.ReplayValidationException exception =
                assertThrows(org.example.wavepilot.replay.ReplayService.ReplayValidationException.class,
                        () -> stack.replayService().startReplay(job.getJobId(), null));
        assertTrue(exception.getMessage().contains("hash or size changed"));
    }

    @Test
    void noApiKeyLeaksIntoMainResources() throws Exception {
        String yaml = new String(SecurityBoundaryIntegrationTest.class
                .getResourceAsStream("/application.yml").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(yaml.contains("${DASHSCOPE_API_KEY:"), "the API key must stay a placeholder");
        assertFalse(yaml.contains("sk-"), "no literal API key may appear in application.yml");
    }

}
