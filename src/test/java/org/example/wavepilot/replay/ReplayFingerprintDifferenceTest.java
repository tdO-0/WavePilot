package org.example.wavepilot.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.evaluation.ReplayFingerprintInput;
import org.example.wavepilot.evaluation.ReplayFingerprintService;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.WavePilotTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReplayFingerprintDifferenceTest {

    private final ReplayFingerprintService service =
            new ReplayFingerprintService(new ObjectMapper().findAndRegisterModules());
    private final ExperimentSpec spec = WavePilotTestFixtures.validSpec();

    @Test
    void differentRandomSeedChangesTheFingerprint() {
        assertNotEquals(fingerprint(spec), fingerprint(specWithSeed(21)));
    }

    @Test
    void differentTemplateVersionChangesTheFingerprint() {
        assertNotEquals(fingerprint(spec),
                service.fingerprint(new ReplayFingerprintInput(spec, "mock-polar-k-v1", 20L,
                        "abc123", config(), List.of("hash1"))));
    }

    @Test
    void differentMatlabScriptHashChangesTheFingerprint() {
        assertNotEquals(fingerprint(spec),
                service.fingerprint(new ReplayFingerprintInput(spec, "polar-k-identification-simple-v1", 20L,
                        "different", config(), List.of("hash1"))));
    }

    @Test
    void differentSpecChangesTheFingerprint() {
        ExperimentSpec other = new ExperimentSpec(spec.experimentType(), List.of(32, 64, 128),
                spec.errorRateStart(), spec.errorRateEnd(), spec.errorRateStep(), spec.sampleCount(),
                spec.monteCarloTimes(), spec.randomSeed(), spec.outputTypes(), spec.description());
        assertNotEquals(fingerprint(spec), fingerprint(other));
    }

    @Test
    void differentCriticalConfigChangesTheFingerprint() {
        assertNotEquals(fingerprint(spec),
                service.fingerprint(new ReplayFingerprintInput(spec, "polar-k-identification-simple-v1", 20L,
                        "abc123", Map.of("runnerType", "mock"), List.of("hash1"))));
    }

    @Test
    void differentArtifactHashesChangeTheFingerprint() {
        assertNotEquals(fingerprint(spec),
                service.fingerprint(new ReplayFingerprintInput(spec, "polar-k-identification-simple-v1", 20L,
                        "abc123", config(), List.of("tampered"))));
    }

    private ExperimentSpec specWithSeed(long seed) {
        return new ExperimentSpec(spec.experimentType(), spec.codeLengths(), spec.errorRateStart(),
                spec.errorRateEnd(), spec.errorRateStep(), spec.sampleCount(), spec.monteCarloTimes(),
                seed, spec.outputTypes(), spec.description());
    }

    private Map<String, String> config() {
        return Map.of("runnerType", "local-matlab",
                "algorithmName", "polar-bsc-binomial-k-baseline",
                "algorithmVersion", "1.0.0");
    }

    private String fingerprint(ExperimentSpec spec) {
        return service.fingerprint(new ReplayFingerprintInput(spec,
                "polar-k-identification-simple-v1", spec.randomSeed(), "abc123", config(),
                List.of("hash1")));
    }
}
