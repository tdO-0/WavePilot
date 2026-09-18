package org.example.wavepilot.experiment.lease;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stable per-boot identity of an executing node. Every claim binds this id to the job;
 * fencing comparisons use it to tell "the current owner" from "a zombie that came back".
 */
@Component
@ConditionalOnExpression("'${wavepilot.node-role:standalone}' != 'api'")
public class WorkerIdentity {
    private final String workerId;

    public WorkerIdentity(@Value("${wavepilot.worker-id:}") String configured) {
        this.workerId = (configured == null || configured.isBlank())
                ? "WORKER-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase()
                : configured;
    }

    public String workerId() { return workerId; }
}
