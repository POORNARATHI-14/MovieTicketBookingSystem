package com.movieticket.events;

import com.movieticket.config.AppProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rabbitmq.enabled", havingValue = "true")
public class RabbitEventMessagePublisher implements EventMessagePublisher {

    private final RabbitTemplate rabbit;
    private final AppProperties props;

    public RabbitEventMessagePublisher(RabbitTemplate rabbit, AppProperties props) {
        this.rabbit = rabbit;
        this.props = props;
    }

    @Override
    public void publish(OutboxEvent event) {
        String routingKey = EventType.valueOf(event.getEventType()).routingKey();
        Message message = MessageBuilder
                .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId("outbox-" + event.getId())
                .setHeader("eventType", event.getEventType())
                .build();
        // Wait for the broker's publisher confirm; throws if the message was not accepted,
        // so the outbox row stays PENDING and is retried.
        rabbit.invoke(ops -> {
            ops.send(props.rabbitmq().exchange(), routingKey, message);
            ops.waitForConfirmsOrDie(5000);
            return Boolean.TRUE;
        });
    }
}
