package org.example.wavepilot.scientific;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.scientific.ledger.ExecutionLedgerEntry;
import org.example.wavepilot.scientific.ledger.ExecutionLedgerStatus;
import org.example.wavepilot.scientific.ledger.ExperimentSpecFingerprint;
import org.example.wavepilot.scientific.ledger.FileExecutionLedgerRepository;
import org.example.wavepilot.scientific.ledger.LedgerArtifactReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionLedgerRecoveryTest {
    @TempDir Path temporary;

    @Test
    void completedExecutionAndValidatedArtifactSurviveRepositoryAndRegistryRestart() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path artifacts = temporary.resolve("artifacts");
        ArtifactRegistry registry = new ArtifactRegistry(artifacts.toString(), mapper);
        registry.writeJson("JOB-LEDGER", ArtifactType.SUMMARY_JSON, "summary.json",
                Map.of("averageAccuracy", .91, "mock", true));
        registry.markJobValidated("JOB-LEDGER", "mock", true, false,
                "SIMPLIFIED_BASELINE", "v1", "v1");
        ArtifactRecord artifact = registry.listByJobId("JOB-LEDGER").get(0);
        ExperimentSpec spec = spec(Map.of("alpha", 1, "beta", 2));
        String fingerprint = new ExperimentSpecFingerprint(mapper).sha256(spec);
        FileExecutionLedgerRepository first = new FileExecutionLedgerRepository(
                temporary.resolve("ledger").toString(), mapper);
        first.save(new ExecutionLedgerEntry("EXEC-RECOVERY-001", "RUN-RECOVERY-001",
                "JOB-LEDGER", fingerprint, ExecutionLedgerStatus.COMPLETED,
                List.of(LedgerArtifactReference.from(artifact)), Map.of("averageAccuracy", .91),
                0, Instant.EPOCH, Instant.now(), null));

        FileExecutionLedgerRepository afterRestart = new FileExecutionLedgerRepository(
                temporary.resolve("ledger").toString(), mapper);
        ExecutionLedgerEntry restored = afterRestart.findByExecutionId("EXEC-RECOVERY-001").orElseThrow();
        ArtifactRegistry registryAfterRestart = new ArtifactRegistry(artifacts.toString(), mapper);

        assertEquals(ExecutionLedgerStatus.COMPLETED, restored.currentStatus());
        assertEquals("JOB-LEDGER", restored.jobId());
        assertTrue(registryAfterRestart.resolveVerifiedReference(
                restored.artifactReferences().get(0).relativePath(),
                restored.artifactReferences().get(0).sha256(),
                restored.artifactReferences().get(0).size()).toFile().isFile());
    }

    @Test
    void runningSideEffectRemainsUncertainAndFingerprintIsCanonical() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ExperimentSpecFingerprint fingerprints = new ExperimentSpecFingerprint(mapper);
        Map<String, Object> firstOrder = new LinkedHashMap<>();
        firstOrder.put("beta", 2);
        firstOrder.put("alpha", 1);
        Map<String, Object> secondOrder = new LinkedHashMap<>();
        secondOrder.put("alpha", 1);
        secondOrder.put("beta", 2);
        assertEquals(fingerprints.sha256(spec(firstOrder)), fingerprints.sha256(spec(secondOrder)));

        FileExecutionLedgerRepository repository = new FileExecutionLedgerRepository(
                temporary.resolve("uncertain-ledger").toString(), mapper);
        repository.save(new ExecutionLedgerEntry("EXEC-UNCERTAIN-001", "RUN-RECOVERY-001",
                "JOB-MAYBE", fingerprints.sha256(spec(firstOrder)), ExecutionLedgerStatus.RUNNING,
                List.of(), Map.of(), 0, Instant.now(), null, null));
        assertEquals(ExecutionLedgerStatus.RUNNING,
                repository.findByExecutionId("EXEC-UNCERTAIN-001").orElseThrow().currentStatus());
    }

    private ExperimentSpec spec(Map<String, Object> custom) {
        return new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), 0, .1, .05, 20, 10, 20,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "ledger test", null, custom);
    }
}
