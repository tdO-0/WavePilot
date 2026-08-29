package org.example.wavepilot.scientific.model;

import java.time.Instant;
import java.util.List;

public record VerificationResult(
        int iteration,
        boolean passed,
        boolean artifactsComplete,
        boolean goalSatisfied,
        boolean grounded,
        Double metricValue,
        List<String> messages,
        Instant verifiedAt) {
    public VerificationResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
        verifiedAt = verifiedAt == null ? Instant.now() : verifiedAt;
        passed = artifactsComplete && goalSatisfied && grounded;
    }
}
