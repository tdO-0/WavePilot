package org.example.wavepilot.experiment.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
@ConditionalOnProperty(name = "wavepilot.node-role", havingValue = "worker")
public class ExperimentExecutionConsumer {
    private static final Logger log = LoggerFactory.getLogger(ExperimentExecutionConsumer.class);
    private final ExperimentService service;
    private final ObjectMapper json;

    public ExperimentExecutionConsumer(ExperimentService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @RabbitListener(queues = BackendRabbitConfiguration.QUEUE, containerFactory = "backendListenerFactory")
    public void consume(Message raw, Channel channel) throws IOException {
        long tag = raw.getMessageProperties().getDeliveryTag();
        ExperimentExecutionMessage message;
        try {
            message = json.readerFor(ExperimentExecutionMessage.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(raw.getBody());
            if (message == null) throw new IllegalArgumentException("Null message");
        } catch (Exception invalid) {
            log.warn("Rejecting invalid experiment message", invalid);
            channel.basicReject(tag, false);
            return;
        }
        // At-least-once delivery + idempotent DB claim. Initial attempt + at most 3 retries.
        // Retries stay on this unacknowledged delivery; never use an infinite basicNack(requeue=true).
        for (int attempt = 0; attempt <= 3; attempt++) {
            try {
                boolean executed = service.executeQueued(message.jobId());
                log.info("Execution {} job {}: {}", message.executionId(), message.jobId(),
                        executed ? "completed" : "duplicate acknowledged");
            } catch (TransientDataAccessException | DataAccessResourceFailureException temporary) {
                if (attempt < 3) {
                    log.warn("Temporary failure job {}, retry {}/3", message.jobId(), attempt + 1);
                    try {
                        Thread.sleep(100L << attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        channel.basicReject(tag, false);
                        return;
                    }
                    continue;
                }
                log.error("Retries exhausted for {}; rejecting to DLQ", message.jobId(), temporary);
                channel.basicReject(tag, false);
                return;
            } catch (RuntimeException permanent) {
                // Deterministic experiment failures and ambiguous post-claim failures are not rerun.
                log.error("Execution failed for {}; rejecting to DLQ", message.jobId(), permanent);
                channel.basicReject(tag, false);
                return;
            }
            // ACK errors are deliberately outside the retry block: never rerun work because ACK failed.
            channel.basicAck(tag, false);
            return;
        }
    }
}
