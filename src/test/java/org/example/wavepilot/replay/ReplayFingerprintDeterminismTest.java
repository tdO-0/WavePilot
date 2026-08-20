package org.example.wavepilot.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.evaluation.ReplayFingerprintInput;
import org.example.wavepilot.evaluation.ReplayFingerprintService;
import org.example.wavepilot.WavePilotTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayFingerprintDeterminismTest {

    private final ReplayFingerprintService service =
            new ReplayFingerprintService(new ObjectMapper().findAndRegisterModules());

    @Test
    void sameInputProducesSameFingerprint() {
        ReplayFingerprintInput first = input();
        ReplayFingerprintInput second = input();

        assertEquals(service.fingerprint(first), service.fingerprint(second));
        assertEquals(service.fingerprint(first), service.fingerprint(first));
    }

    @Test
    void mapAndListOrderDoesNotChangeTheFingerprint() {
        Map<String, String> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put("runnerType", "local-matlab");
        reverseOrder.put("algorithmName", "polar-bsc-binomial-k-baseline");
        reverseOrder.put("algorithmVersion", "1.0.0");
        ReplayFingerprintInput ordered = new ReplayFingerprintInput(
                WavePilotTestFixtures.validSpec(), "polar-k-identification-simple-v1", 20L,
                "abc123", reverseOrder, List.of("hash1", "hash2"));

        assertEquals(service.fingerprint(input()), service.fingerprint(ordered));
    }

    @Test
    void fingerprintIsAStableSha256HexDigest() {
        String fingerprint = service.fingerprint(input());
        assertTrue(fingerprint.matches("[0-9a-f]{64}"), "fingerprint must be 64 lowercase hex chars");
        assertFalse(fingerprint.equalsIgnoreCase(fingerprint.replace('a', 'b')));
    }

    private ReplayFingerprintInput input() {
        return new ReplayFingerprintInput(
                WavePilotTestFixtures.validSpec(), "polar-k-identification-simple-v1", 20L,
                "abc123", Map.of(
                "runnerType", "local-matlab",
                "algorithmName", "polar-bsc-binomial-k-baseline",
                "algorithmVersion", "1.0.0"),
                List.of("hash1", "hash2"));
    }
}
