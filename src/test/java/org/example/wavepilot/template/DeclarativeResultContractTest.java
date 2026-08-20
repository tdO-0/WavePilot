package org.example.wavepilot.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.validation.DeclarativeResultContractValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarativeResultContractTest {

    @TempDir Path root;

    @Test
    void validCsvPassesTheDeclaredContract() throws Exception {
        List<String> errors = validate(validCsv());
        assertTrue(errors.isEmpty(), "valid CSV must pass: " + errors);
    }

    @Test
    void missingColumnsNaNInfAndBoundsAreAllRejected() throws Exception {
        List<String> missingColumn = validate("ebNo,berSim\n0,0.1\n1,0.2\n");
        assertTrue(missingColumn.stream().anyMatch(error -> error.contains("berTheory")),
                "missing required column must be reported: " + missingColumn);

        List<String> nan = validate("ebNo,berSim,berTheory\n0,NaN,0.1\n1,0.2,0.3\n");
        assertTrue(nan.stream().anyMatch(error -> error.contains("NaN")),
                "NaN must be rejected: " + nan);

        List<String> outOfBounds = validate("ebNo,berSim,berTheory\n0,1.5,0.1\n1,0.2,0.3\n");
        assertTrue(outOfBounds.stream().anyMatch(error -> error.contains("outside")),
                "out-of-bounds value must be rejected: " + outOfBounds);
    }

    @Test
    void requiredArtifactsAreEnforced() throws Exception {
        List<String> errors = validate(validCsv(), false);
        assertTrue(errors.stream().anyMatch(error -> error.contains("ACCURACY_CURVE")),
                "missing required artifact must be reported: " + errors);
    }

    @Test
    void mandatorySummaryFieldsAreEnforced() throws Exception {
        List<String> errors = validate(validCsv(), true, "{\"experimentType\":\"demo-ber-awgn\"}");
        assertTrue(errors.stream().anyMatch(error -> error.contains("algorithmName")),
                "missing summary field must be reported: " + errors);
    }

    private List<String> validate(String csvContent) throws Exception {
        return validate(csvContent, true, "{\"experimentType\":\"demo-ber-awgn\","
                + "\"algorithmName\":\"demo\",\"rowCount\":2}");
    }

    private List<String> validate(String csvContent, boolean withCurve) throws Exception {
        return validate(csvContent, withCurve, "{\"experimentType\":\"demo-ber-awgn\","
                + "\"algorithmName\":\"demo\",\"rowCount\":2}");
    }

    private List<String> validate(String csvContent, boolean withCurve, String summaryJson) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ArtifactRegistry registry = new ArtifactRegistry(root.toString(), mapper);
        ExperimentSpec spec = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), 0.0, 0.1, 0.05, 20, 10, 20L,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "contract test",
                DeclarativeTestSupport.DEMO_TYPE_ID, Map.of());
        ExperimentPlan plan = new ExperimentPlan("PLAN-DEMO", spec, "demo-ber-awgn", 2,
                List.of("RUN"), Instant.now());
        ExperimentJob job = new ExperimentJob("JOB-DEMO-1", spec, plan);
        job.changeStatus(ExperimentStatus.SUCCEEDED, "fixture");
        Path jobDirectory = registry.createJobDirectory(job.getJobId());
        Path csv = jobDirectory.resolve("accuracy.csv");
        Files.writeString(csv, csvContent, StandardCharsets.UTF_8);
        registry.register(job.getJobId(), ArtifactType.ACCURACY_CSV, csv);
        Path summary = jobDirectory.resolve("summary.json");
        Files.writeString(summary, summaryJson, StandardCharsets.UTF_8);
        registry.register(job.getJobId(), ArtifactType.SUMMARY_JSON, summary);
        Path log = jobDirectory.resolve("run.log");
        Files.writeString(log, "log", StandardCharsets.UTF_8);
        registry.register(job.getJobId(), ArtifactType.RUN_LOG, log);
        if (withCurve) {
            Path png = jobDirectory.resolve("curve.png");
            Files.write(png, new byte[]{1, 2, 3});
            registry.register(job.getJobId(), ArtifactType.ACCURACY_CURVE, png);
        }

        Map<ArtifactType, Path> byType = new EnumMap<>(ArtifactType.class);
        for (var record : registry.listByJobId(job.getJobId())) {
            byType.put(record.type(), registry.resolveVerified(record.artifactId()));
        }
        List<String> errors = new ArrayList<>();
        new DeclarativeResultContractValidator(DeclarativeTestSupport.registryWithDemo(), mapper)
                .validate(job, byType, errors);
        return errors;
    }

    private String validCsv() {
        return "ebNo,berSim,berTheory\n0,0.1,0.09\n1,0.2,0.21\n";
    }
}
