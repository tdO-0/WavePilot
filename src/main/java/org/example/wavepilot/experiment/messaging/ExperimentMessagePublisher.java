package org.example.wavepilot.experiment.messaging;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.concurrent.TimeUnit;

public class ExperimentMessagePublisher {
    private final RabbitTemplate rabbit;

    public ExperimentMessagePublisher(RabbitTemplate rabbit) { this.rabbit = rabbit; }

    public void publish(String jobId) {
        ExperimentExecutionMessage message = ExperimentExecutionMessage.forJob(jobId);
        CorrelationData correlation = new CorrelationData(message.messageId());
        rabbit.convertAndSend(BackendRabbitConfiguration.EXCHANGE, BackendRabbitConfiguration.ROUTING_KEY,
                message, raw -> {
                    raw.getMessageProperties().setMessageId(message.messageId());
                    raw.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return raw;
                }, correlation);
        try {
            if (!correlation.getFuture().get(5, TimeUnit.SECONDS).isAck() || correlation.getReturned() != null)
                throw new IllegalStateException("Broker rejected or returned message for " + jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publish interrupted for " + jobId, e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Publish confirmation unavailable for " + jobId, e);
        }
    }
}
