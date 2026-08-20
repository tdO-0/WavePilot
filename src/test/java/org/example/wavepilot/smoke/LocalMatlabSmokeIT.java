package org.example.wavepilot.smoke;

import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.runner.ExperimentRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("matlab-smoke")
class LocalMatlabSmokeIT {

    @Autowired private ExperimentService experimentService;
    @Autowired private ExperimentRunner experimentRunner;

    @Test
    void realMatlabRunsFixedTemplateAndProducesValidatedArtifacts() throws Exception {
        assertEquals("local-matlab", experimentRunner.runnerType());
        ExperimentSpec spec = new ExperimentSpec(
                ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32, 64),
                0.0, 0.10, 0.05,
                50, 10, 20L,
                List.of(OutputType.ACCURACY_CSV, OutputType.MAT_RESULT,
                        OutputType.ACCURACY_CURVE, OutputType.RUN_LOG),
                "Phase 4 real local MATLAB smoke");

        ExperimentJob job = experimentService.create(spec);
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        while (!job.getStatus().isTerminal() && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }

        assertEquals(ExperimentStatus.SUCCEEDED, job.getStatus(), job.getFailureReason());
        assertNotNull(job.getExternalJobId());
        assertTrue(job.getExternalJobId().startsWith("MATLAB-"));

        List<ArtifactRecord> artifacts = experimentService.artifacts(job.getJobId());
        assertEquals(7, artifacts.size(), artifacts.toString());
        Map<ArtifactType, ArtifactRecord> byType = new EnumMap<>(ArtifactType.class);
        artifacts.forEach(artifact -> byType.put(artifact.type(), artifact));
        for (ArtifactType required : List.of(
                ArtifactType.EXPERIMENT_SPEC, ArtifactType.EXPERIMENT_PLAN,
                ArtifactType.ACCURACY_CSV, ArtifactType.MAT_RESULT,
                ArtifactType.ACCURACY_CURVE, ArtifactType.SUMMARY_JSON,
                ArtifactType.RUN_LOG)) {
            assertTrue(byType.containsKey(required), "Missing artifact " + required);
            assertTrue(Files.isRegularFile(Path.of(byType.get(required).path())));
            assertTrue(byType.get(required).size() > 0);
            assertEquals(64, byType.get(required).sha256().length());
        }

        ExperimentService.ExperimentSummaryView summary =
                experimentService.readExperimentSummary(job.getJobId());
        assertFalse(summary.mock());
        assertEquals("local-matlab", summary.values().get("runnerType"));
        assertEquals("polar-bsc-binomial-k-baseline", summary.values().get("algorithmName"));
        assertEquals("1.0.0", summary.values().get("algorithmVersion"));
        assertEquals(Boolean.FALSE, summary.values().get("algorithmValidated"));
        assertEquals("BSC_BIT_FLIP_PROBABILITY", summary.values().get("errorRateMeaning"));
        assertEquals(6, ((Number) summary.values().get("completedPoints")).intValue());
        String matlabVersion = String.valueOf(summary.values().get("matlabVersion"));
        assertFalse(matlabVersion.isBlank());

        String runLog = Files.readString(Path.of(byType.get(ArtifactType.RUN_LOG).path()));
        assertTrue(runLog.contains("WAVEPILOT_MATLAB_VERSION="), runLog);
        assertTrue(runLog.contains("algorithm=polar-bsc-binomial-k-baseline"), runLog);
        assertTrue(runLog.contains("WAVEPILOT_RESULT"), runLog);
        assertTrue(runLog.contains("mock=false"), runLog);
        assertTrue(runLog.contains("algorithmValidated=false"), runLog);

        System.out.printf("MATLAB_SMOKE version=%s jobId=%s rows=%s averageAccuracy=%s "
                        + "csv=%s mat=%s png=%s log=%s%n",
                matlabVersion, job.getJobId(), summary.values().get("completedPoints"),
                summary.values().get("meanAccuracy"),
                byType.get(ArtifactType.ACCURACY_CSV).path(),
                byType.get(ArtifactType.MAT_RESULT).path(),
                byType.get(ArtifactType.ACCURACY_CURVE).path(),
                byType.get(ArtifactType.RUN_LOG).path());
    }
}
