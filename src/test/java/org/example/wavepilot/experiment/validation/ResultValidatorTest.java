package org.example.wavepilot.experiment.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.runner.ProducedArtifact;
import org.example.wavepilot.runner.RunnerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultValidatorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void acceptsCompleteCsvAndConsistentSummary() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResultValidator validator = new ResultValidator(mapper, new ExperimentSpecValidator());
        ExperimentJob job = WavePilotTestFixtures.job("JOB-RESULT-1");
        List<ProducedArtifact> artifacts = writeArtifacts(mapper, false);

        ValidationResult result = validator.validate(job, succeededStatus(), artifacts);

        assertTrue(result.valid(), () -> String.join("; ", result.errors()));
    }

    @Test
    void rejectsNaNMissingPointsAndSummaryMismatch() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResultValidator validator = new ResultValidator(mapper, new ExperimentSpecValidator());
        ExperimentJob job = WavePilotTestFixtures.job("JOB-RESULT-2");
        List<ProducedArtifact> artifacts = writeArtifacts(mapper, true);

        ValidationResult result = validator.validate(job, succeededStatus(), artifacts);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("finite")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("Missing result point")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("summary.json")));
    }

    @Test
    void rejectsUnrecognizedRequestedMatAndPngFiles() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResultValidator validator = new ResultValidator(mapper, new ExperimentSpecValidator());
        ExperimentSpec base = WavePilotTestFixtures.validSpec();
        ExperimentSpec spec = new ExperimentSpec(base.experimentType(), base.codeLengths(),
                base.errorRateStart(), base.errorRateEnd(), base.errorRateStep(),
                base.sampleCount(), base.monteCarloTimes(), base.randomSeed(),
                List.of(OutputType.ACCURACY_CSV, OutputType.MAT_RESULT,
                        OutputType.ACCURACY_CURVE, OutputType.RUN_LOG), base.description());
        ExperimentJob job = new ExperimentJob("JOB-RESULT-FORMATS",
                spec, new ExperimentPlan("PLAN-FORMATS", spec, "test", 6, List.of(), Instant.now()));
        List<ProducedArtifact> artifacts = new java.util.ArrayList<>(writeArtifacts(mapper, false));
        Path mat = tempDirectory.resolve("result.mat");
        Path png = tempDirectory.resolve("accuracy-curve.png");
        Files.writeString(mat, "not a MAT file");
        Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        artifacts.add(new ProducedArtifact(ArtifactType.MAT_RESULT, mat));
        artifacts.add(new ProducedArtifact(ArtifactType.ACCURACY_CURVE, png));

        ValidationResult result = validator.validate(job, succeededStatus(), artifacts);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("MAT-file signature")));
        assertTrue(result.errors().stream().anyMatch(error -> error.toLowerCase().contains("decod")));
    }

    @Test
    void rejectsUnexpectedCsvResultPoint() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResultValidator validator = new ResultValidator(mapper, new ExperimentSpecValidator());
        ExperimentJob job = WavePilotTestFixtures.job("JOB-RESULT-EXTRA");
        List<ProducedArtifact> artifacts = writeArtifacts(mapper, false);
        Path csv = artifacts.stream()
                .filter(artifact -> artifact.type() == ArtifactType.ACCURACY_CSV)
                .findFirst().orElseThrow().path();
        Files.writeString(csv, "128,0.5,0.1\n", java.nio.file.StandardOpenOption.APPEND);

        ValidationResult result = validator.validate(job, succeededStatus(), artifacts);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("Unexpected result point")));
    }

    private List<ProducedArtifact> writeArtifacts(ObjectMapper mapper, boolean corrupt) throws Exception {
        Path csv = tempDirectory.resolve("accuracy.csv");
        Path summary = tempDirectory.resolve("summary.json");
        Path log = tempDirectory.resolve("run.log");
        String csvText = corrupt
                ? "codeLength,errorRate,accuracy\n32,0,NaN\n"
                : "codeLength,errorRate,accuracy\n"
                    + "32,0,0.90\n32,0.01,0.80\n32,0.02,0.70\n"
                    + "64,0,0.85\n64,0.01,0.75\n64,0.02,0.65\n";
        Files.writeString(csv, csvText);
        mapper.writeValue(summary.toFile(), corrupt
                ? Map.of("mock", false, "rowCount", 99, "averageAccuracy", 9.0)
                : Map.of("mock", false, "rowCount", 6, "averageAccuracy", 0.775));
        Files.writeString(log, "exitCode=0");
        return List.of(
                new ProducedArtifact(ArtifactType.ACCURACY_CSV, csv),
                new ProducedArtifact(ArtifactType.SUMMARY_JSON, summary),
                new ProducedArtifact(ArtifactType.RUN_LOG, log));
    }

    private RunnerStatus succeededStatus() {
        return new RunnerStatus("MOCK-RESULT", RunnerStatus.State.SUCCEEDED, 100, 6, 6,
                "done", 0, Instant.now());
    }
}
