package org.example.wavepilot.experiment.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableRabbit
@ConditionalOnExpression("'${wavepilot.node-role:standalone}' == 'api' or '${wavepilot.node-role:standalone}' == 'worker'")
public class BackendRabbitConfiguration {
    public static final String EXCHANGE = "wavepilot.experiments";
    public static final String QUEUE = "wavepilot.experiments.execute";
    public static final String ROUTING_KEY = "experiment.execute";
    public static final String DLX = "wavepilot.experiments.dlx";
    public static final String DLQ = "wavepilot.experiments.dead";
    public static final String DEAD_KEY = "experiment.dead";

    @Bean
    public CachingConnectionFactory rabbitConnectionFactory(Environment env) {
        if (!"mysql".equals(env.getProperty("wavepilot.job-repository")))
            throw new IllegalStateException("api/worker require wavepilot.job-repository=mysql");
        CachingConnectionFactory factory = new CachingConnectionFactory(
                env.getProperty("spring.rabbitmq.host", "localhost"),
                env.getProperty("spring.rabbitmq.port", Integer.class, 5672));
        factory.setUsername(env.getRequiredProperty("spring.rabbitmq.username"));
        factory.setPassword(env.getRequiredProperty("spring.rabbitmq.password"));
        factory.setVirtualHost(env.getProperty("spring.rabbitmq.virtual-host", "/"));
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    @Bean
    public Declarables experimentTopology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        DirectExchange dead = new DirectExchange(DLX, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).deadLetterExchange(DLX)
                .deadLetterRoutingKey(DEAD_KEY).build();
        Queue dlq = QueueBuilder.durable(DLQ).build();
        return new Declarables(exchange, dead, queue, dlq,
                BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY),
                BindingBuilder.bind(dlq).to(dead).with(DEAD_KEY));
    }

    @Bean
    public RabbitAdmin rabbitAdmin(CachingConnectionFactory factory) { return new RabbitAdmin(factory); }

    @Bean
    public RabbitTemplate rabbitTemplate(CachingConnectionFactory factory, ObjectMapper mapper) {
        RabbitTemplate rabbit = new RabbitTemplate(factory);
        rabbit.setMandatory(true); // Detect an unroutable publish, not just a successful socket write.
        rabbit.setMessageConverter(new Jackson2JsonMessageConverter(mapper));
        return rabbit;
    }

    @Bean
    public ExperimentMessagePublisher experimentMessagePublisher(RabbitTemplate rabbit) {
        return new ExperimentMessagePublisher(rabbit);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory backendListenerFactory(CachingConnectionFactory factory) {
        SimpleRabbitListenerContainerFactory listener = new SimpleRabbitListenerContainerFactory();
        listener.setConnectionFactory(factory);
        listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setPrefetchCount(1); // A long MATLAB job must not reserve a batch of waiting jobs.
        listener.setDefaultRequeueRejected(false);
        return listener;
    }
}
