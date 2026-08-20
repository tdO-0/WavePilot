package org.example.wavepilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.WavePilotTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReplayFingerprintTest {

    private final ReplayFingerprintService service =
            new ReplayFingerprintService(new ObjectMapper().findAndRegisterModules());

    @Test
    void canonicalMapOrderProducesSameFingerprint() {
        Map<String, String> firstConfig = new LinkedHashMap<>();
        firstConfig.put("runner", "mock");
        firstConfig.put("timeout", "60s");
        Map<String, String> secondConfig = new LinkedHashMap<>();
        secondConfig.put("timeout", "60s");
        secondConfig.put("runner", "mock");

        ReplayFingerprintInput first = input(20L, firstConfig);
        ReplayFingerprintInput second = input(20L, secondConfig);

        assertEquals(service.fingerprint(first), service.fingerprint(second));
    }

    @Test
    void changedSeedChangesFingerprint() {
        assertNotEquals(service.fingerprint(input(20L, Map.of("runner", "mock"))),
                service.fingerprint(input(21L, Map.of("runner", "mock"))));
    }

    private ReplayFingerprintInput input(long seed, Map<String, String> config) {
        return new ReplayFingerprintInput(WavePilotTestFixtures.validSpec(), "mock-polar-k-v1", seed,
                "script-sha256", config, List.of("artifact-sha256"));
    }
}
