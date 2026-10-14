package com.movieticket.events;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Demo consumer proving the full path DB -> outbox -> RabbitMQ -> consumer works. It just logs. */
@Component
@ConditionalOnProperty(name = "app.rabbitmq.enabled", havingValue = "true")
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    @RabbitListener(queues = "${app.rabbitmq.queue}")
    public void onEvent(Message message) {
        log.info("[RabbitMQ consumer] {} -> {}", message.getMessageProperties().getReceivedRoutingKey(),
                new String(message.getBody(), StandardCharsets.UTF_8));
    }
}
