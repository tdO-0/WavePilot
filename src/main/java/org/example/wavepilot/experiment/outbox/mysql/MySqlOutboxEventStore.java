package org.example.wavepilot.experiment.outbox.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.outbox.OutboxEventStatus;
import org.example.wavepilot.experiment.outbox.OutboxEventStore;
import org.example.wavepilot.experiment.outbox.OutboxPayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
@org.springframework.context.annotation.DependsOn("backendFlyway")
@ConditionalOnProperty(name = "wavepilot.job-repository", havingValue = "mysql")
public class MySqlOutboxEventStore implements OutboxEventStore {
    private final OutboxEventMapper mapper;
    private final ObjectMapper json;

    public MySqlOutboxEventStore(OutboxEventMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    /**
     * Idempotent per job: the unique key on {@code job_id} means a duplicate append keeps
     * the first event. Called inside the caller's transaction, so a job transition and its
     * dispatch event commit (or roll back) together.
     */
    @Override
    public void append(String jobId) {
        OutboxPayload payload = OutboxPayload.forJob(jobId);
        OutboxEventRow row = new OutboxEventRow();
        row.jobId = jobId;
        row.eventType = "EXPERIMENT_EXECUTE";
        row.status = OutboxEventStatus.PENDING.name();
        row.attemptCount = 0;
        row.nextRetryAt = payload.createdAt();
        row.createdAt = payload.createdAt();
        try {
            row.payloadJson = json.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize outbox payload for " + jobId, e);
        }
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException conflict) {
            // The first event (possibly already SENT) remains authoritative.
        }
    }
}
