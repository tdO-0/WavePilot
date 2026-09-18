package org.example.wavepilot.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.outbox.mysql.MySqlOutboxEventStore;
import org.example.wavepilot.experiment.outbox.mysql.OutboxEventMapper;
import org.example.wavepilot.experiment.outbox.mysql.OutboxEventRow;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MySqlOutboxEventStoreTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    private final MySqlOutboxEventStore store = new MySqlOutboxEventStore(mapper, json);

    @Test
    void appendInsertsPendingEventWithValidPayload() throws Exception {
        store.append("JOB-OUTBOX-1");
        var captured = org.mockito.ArgumentCaptor.forClass(OutboxEventRow.class);
        verify(mapper).insert(captured.capture());
        var row = captured.getValue();
        assertEquals("JOB-OUTBOX-1", row.jobId);
        assertEquals("EXPERIMENT_EXECUTE", row.eventType);
        assertEquals("PENDING", row.status);
        assertEquals(0, row.attemptCount);
        assertNotNull(row.nextRetryAt);
        var payload = json.readTree(row.payloadJson);
        assertEquals("JOB-OUTBOX-1", payload.get("jobId").asText());
        assertNotNull(payload.get("messageId").asText());
        assertEquals("EXEC-JOB-OUTBOX-1", payload.get("executionId").asText());
    }

    @Test
    void duplicateAppendKeepsTheFirstEvent() {
        when(mapper.insert(any(OutboxEventRow.class))).thenThrow(new DuplicateKeyException("uk_outbox_event_job"));
        assertDoesNotThrow(() -> store.append("JOB-OUTBOX-1"));
        verify(mapper).insert(any(OutboxEventRow.class));
    }

    @Test
    void nonDuplicateFailurePropagatesToTheTransaction() {
        when(mapper.insert(any(OutboxEventRow.class)))
                .thenThrow(new IllegalStateException("table unavailable"));
        assertThrows(IllegalStateException.class, () -> store.append("JOB-OUTBOX-2"));
    }
}
