package org.example.wavepilot.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.wavepilot.experiment.messaging.*;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.TransientDataAccessResourceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackendConsumerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ExperimentService service = mock(ExperimentService.class);
    private final Channel channel = mock(Channel.class);
    private final ExperimentExecutionConsumer consumer = new ExperimentExecutionConsumer(service, json);

    private Message message() throws Exception {
        var properties = new MessageProperties(); properties.setDeliveryTag(7);
        return new Message(json.writeValueAsBytes(ExperimentExecutionMessage.forJob("JOB-CONSUME")), properties);
    }

    @Test
    void successAcknowledgesOnce() throws Exception {
        when(service.executeQueued("JOB-CONSUME")).thenReturn(true);
        consumer.consume(message(), channel);
        verify(channel).basicAck(7, false); verifyNoMoreInteractions(channel);
    }

    @Test
    void duplicateAcknowledgesWithoutAnotherExecution() throws Exception {
        when(service.executeQueued("JOB-CONSUME")).thenReturn(false);
        consumer.consume(message(), channel);
        verify(channel).basicAck(7, false); verifyNoMoreInteractions(channel);
    }

    @Test
    void temporaryFailureRetriesThenAcknowledges() throws Exception {
        when(service.executeQueued("JOB-CONSUME"))
                .thenThrow(new TransientDataAccessResourceException("temporary"))
                .thenReturn(true);
        consumer.consume(message(), channel);
        verify(service, times(2)).executeQueued("JOB-CONSUME");
        verify(channel).basicAck(7, false); verifyNoMoreInteractions(channel);
    }

    @Test
    void exactlyThreeRetriesThenRejectWithoutRequeue() throws Exception {
        when(service.executeQueued("JOB-CONSUME")).thenThrow(new TransientDataAccessResourceException("offline"));
        consumer.consume(message(), channel);
        verify(service, times(4)).executeQueued("JOB-CONSUME");
        verify(channel).basicReject(7, false); verifyNoMoreInteractions(channel);
    }

    @Test
    void permanentOrPostClaimFailureIsDeadLetteredWithoutRepeatingSideEffects() throws Exception {
        when(service.executeQueued("JOB-CONSUME")).thenThrow(new IllegalStateException("Runner failed"));
        consumer.consume(message(), channel);
        verify(service).executeQueued("JOB-CONSUME");
        verify(channel).basicReject(7, false); verifyNoMoreInteractions(channel);
    }

    @Test
    void malformedMessageIsRejectedBeforeAccessingService() throws Exception {
        Message valid = message();
        consumer.consume(new Message("{}".getBytes(), valid.getMessageProperties()), channel);
        verifyNoInteractions(service);
        verify(channel).basicReject(7, false);
    }

    @Test
    void lostAckDoesNotRepeatBusinessExecutionInTheSameDelivery() throws Exception {
        when(service.executeQueued("JOB-CONSUME")).thenReturn(true);
        doThrow(new java.io.IOException("connection lost")).when(channel).basicAck(7, false);
        assertThrows(java.io.IOException.class, () -> consumer.consume(message(), channel));
        verify(service).executeQueued("JOB-CONSUME");
        verify(channel).basicAck(7, false); verifyNoMoreInteractions(channel);
    }
}
