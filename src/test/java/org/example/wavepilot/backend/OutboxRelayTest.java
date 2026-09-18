package org.example.wavepilot.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.wavepilot.experiment.messaging.ExperimentMessagePublisher;
import org.example.wavepilot.experiment.outbox.OutboxMetrics;
import org.example.wavepilot.experiment.outbox.OutboxPayload;
import org.example.wavepilot.experiment.outbox.OutboxProperties;
import org.example.wavepilot.experiment.outbox.OutboxRelay;
import org.example.wavepilot.experiment.outbox.mysql.OutboxEventMapper;
import org.example.wavepilot.experiment.outbox.mysql.OutboxEventRow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxRelayTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    private final ExperimentMessagePublisher publisher = mock(ExperimentMessagePublisher.class);
    private final OutboxProperties properties = new OutboxProperties(true,
            50, Duration.ofSeconds(60), Duration.ofSeconds(1), Duration.ofSeconds(300));
    private final OutboxMetrics metrics = new OutboxMetrics(new SimpleMeterRegistry());

    private OutboxRelay relay() {
        return new OutboxRelay(mapper, publisher, json, properties, metrics);
    }

    private OutboxEventRow pending(long id, long version, int attempts) throws Exception {
        OutboxEventRow row = new OutboxEventRow();
        row.id = id; row.version = version; row.attemptCount = attempts;
        row.jobId = "JOB-" + id; row.status = "PENDING";
        row.payloadJson = json.writeValueAsString(OutboxPayload.forJob(row.jobId));
        row.nextRetryAt = Instant.now().minusSeconds(1);
        return row;
    }

    @Test
    void dueEventIsClaimedPublishedAndMarkedSent() throws Exception {
        var row = pending(1, 4, 0);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.claim(eq(1L), eq(4L), any(), any())).thenReturn(1);
        when(mapper.markSent(eq(1L), eq(5L), any())).thenReturn(1);

        relay().relayBatch();

        verify(mapper).claim(eq(1L), eq(4L), any(), any());
        verify(publisher).publish(argThat(
                (org.example.wavepilot.experiment.messaging.ExperimentExecutionMessage message) ->
                        message.jobId().equals(row.jobId)));
        verify(mapper).markSent(eq(1L), eq(5L), any());
    }

    @Test
    void publishFailureKeepsEventPendingWithBackoffAndCountsAttempt() throws Exception {
        var row = pending(2, 7, 0);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.claim(anyLong(), anyLong(), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("broker down")).when(publisher)
                .publish(any(org.example.wavepilot.experiment.messaging.ExperimentExecutionMessage.class));
        var failedMetrics = mock(OutboxMetrics.class);
        var relay = new OutboxRelay(mapper, publisher, json, properties, failedMetrics);

        relay.relayBatch();

        verify(mapper).markFailed(eq(2L), eq(8L), argThat(retryAt ->
                retryAt.isAfter(Instant.now()) && retryAt.isBefore(Instant.now().plusSeconds(3))));
        verify(mapper, never()).markSent(anyLong(), anyLong(), any());
        verify(failedMetrics).publishFailed();
    }

    @Test
    void backoffGrowsExponentiallyAndStaysCapped() {
        Duration base = Duration.ofSeconds(1), cap = Duration.ofSeconds(300);
        assertEquals(Duration.ofSeconds(1), OutboxRelay.backoff(0, base, cap));
        assertEquals(Duration.ofSeconds(2), OutboxRelay.backoff(1, base, cap));
        assertEquals(Duration.ofSeconds(4), OutboxRelay.backoff(2, base, cap));
        assertEquals(Duration.ofSeconds(8), OutboxRelay.backoff(3, base, cap));
        assertEquals(Duration.ofSeconds(300), OutboxRelay.backoff(9, base, cap));
        assertEquals(Duration.ofSeconds(300), OutboxRelay.backoff(20, base, cap));
    }

    @Test
    void lostClaimNeverPublishes() throws Exception {
        var row = pending(3, 1, 0);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.claim(anyLong(), anyLong(), any(), any())).thenReturn(0); // another relay won

        relay().relayBatch();

        verify(publisher, never()).publish(
                any(org.example.wavepilot.experiment.messaging.ExperimentExecutionMessage.class));
        verify(mapper, never()).markSent(anyLong(), anyLong(), any());
        verify(mapper, never()).markFailed(anyLong(), anyLong(), any());
    }

    @Test
    void sentEventsAreNeverClaimedAgain() throws Exception {
        // The relay SELECT already excludes SENT rows; even if one sneaks into the batch,
        // the claim predicate (status='PENDING') must reject it so nothing is published.
        OutboxEventRow row = pending(4, 2, 0);
        row.status = "SENT";
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.claim(anyLong(), anyLong(), any(), any())).thenReturn(0); // real SQL predicate

        relay().relayBatch();

        verify(publisher, never()).publish(
                any(org.example.wavepilot.experiment.messaging.ExperimentExecutionMessage.class));
        verify(mapper, never()).markSent(anyLong(), anyLong(), any());
    }

    @Test
    void relayRestartContinuesUnsentPendingEvents() throws Exception {
        // First relay crashes between claim and publish: the claim lease expires and the
        // row is still PENDING with an older next_retry_at; a fresh relay claims it again.
        var row = pending(5, 3, 1);
        row.nextRetryAt = Instant.now().minusSeconds(61); // claim lease (60s) already expired
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.claim(eq(5L), eq(3L), any(), any())).thenReturn(1);
        when(mapper.markSent(eq(5L), eq(4L), any())).thenReturn(1);
        var retriedMetrics = mock(OutboxMetrics.class);
        var relay = new OutboxRelay(mapper, publisher, json, properties, retriedMetrics);

        relay.relayBatch();

        verify(publisher).publish(any(org.example.wavepilot.experiment.messaging.ExperimentExecutionMessage.class));
        verify(mapper).markSent(eq(5L), eq(4L), any());
        verify(retriedMetrics).retried(); // attempt_count > 0 on claim counts as a retry
    }

    @Test
    void corruptPayloadFailsTheEventInsteadOfKillingTheBatch() throws Exception {
        var bad = pending(6, 0, 0);
        bad.payloadJson = "{\"not\":\"an outbox payload\"}";
        var good = pending(7, 0, 0);
        when(mapper.selectList(any())).thenReturn(List.of(bad, good));
        when(mapper.claim(anyLong(), anyLong(), any(), any())).thenReturn(1);
        when(mapper.markSent(anyLong(), anyLong(), any())).thenReturn(1);
        doThrow(new IllegalStateException("unroutable")).when(publisher)
                .publish(any(org.example.wavepilot.experiment.messaging.ExperimentExecutionMessage.class));

        relay().relayBatch();

        verify(mapper, times(2)).markFailed(anyLong(), anyLong(), any());
        verify(mapper, never()).markSent(anyLong(), anyLong(), any());
    }
}
