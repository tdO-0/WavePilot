package org.example.wavepilot.runner;

import java.time.Instant;

public record RunnerSubmission(String externalJobId, Instant submittedAt) {
}
