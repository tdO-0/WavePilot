package org.example.wavepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.example.wavepilot.experiment.validation.ResultValidator;
import org.example.wavepilot.runner.MatlabTemplateCatalog;
import org.example.wavepilot.runner.ProducedArtifact;
import org.example.wavepilot.runner.RunnerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase4RegressionTest {

    @TempDir Path tempDirectory;

    @Test
    void integrationFixtureKeepsLegacyPhase4ResultContract() throws Exception {
        ExperimentSpec spec = WavePilotTestFixtures.validSpec();
        ExperimentJob job = new ExperimentJob("JOB-PHASE4-FIXTURE", spec,
                new ExperimentPlan("PLAN-PHASE4-FIXTURE", spec,
                        MatlabTemplateCatalog.INTEGRATION_FIXTURE, 6, List.of(), Instant.now()));
        Path csv = tempDirectory.resolve("accuracy.csv");
        Path summary = tempDirectory.resolve("summary.json");
        Path log = tempDirectory.resolve("run.log");
        Files.writeString(csv, "codeLength,errorRate,accuracy\n"
                + "32,0,0.9\n32,0.01,0.8\n32,0.02,0.7\n"
                + "64,0,0.85\n64,0.01,0.75\n64,0.02,0.65\n");
        new ObjectMapper().writeValue(summary.toFile(),
                Map.of("mock", false, "rowCount", 6, "averageAccuracy", 0.775));
        Files.writeString(log, "classification=INTEGRATION_FIXTURE");
        List<ProducedArtifact> artifacts = List.of(
                new ProducedArtifact(ArtifactType.ACCURACY_CSV, csv),
                new ProducedArtifact(ArtifactType.SUMMARY_JSON, summary),
                new ProducedArtifact(ArtifactType.RUN_LOG, log));
        RunnerStatus status = new RunnerStatus("MATLAB-FIXTURE", RunnerStatus.State.SUCCEEDED,
                100, 6, 6, "done", 0, Instant.now());

        var result = new ResultValidator(new ObjectMapper(), new ExperimentSpecValidator())
                .validate(job, status, artifacts);
        assertTrue(result.valid(), () -> String.join("; ", result.errors()));
    }
}
