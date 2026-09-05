package org.example.wavepilot.experiment.messaging;

import java.time.Instant;
import java.util.UUID;

/** Reference-only payload; the database, not the message, supplies the validated Spec. */
public record ExperimentExecutionMessage(String messageId, String jobId, String executionId, Instant createdAt) {
    public ExperimentExecutionMessage {
        if (messageId == null || !messageId.matches("[A-Za-z0-9_-]{1,100}")
                || jobId == null || !jobId.matches("JOB-[A-Za-z0-9_-]{1,96}")
                || executionId == null || !executionId.equals("EXEC-" + jobId) || createdAt == null)
            throw new IllegalArgumentException("Invalid experiment execution message");
    }

    public static ExperimentExecutionMessage forJob(String jobId) {
        return new ExperimentExecutionMessage(UUID.randomUUID().toString(), jobId, "EXEC-" + jobId, Instant.now());
    }
}
