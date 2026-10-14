package com.movieticket.events;

import com.movieticket.config.AppProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declared on the broker (exchange, queue, binding) only when app.rabbitmq.enabled=true. */
@Configuration
@ConditionalOnProperty(name = "app.rabbitmq.enabled", havingValue = "true")
public class RabbitConfig {

    @Bean
    public TopicExchange eventsExchange(AppProperties props) {
        return new TopicExchange(props.rabbitmq().exchange(), true, false);
    }

    @Bean
    public Queue auditQueue(AppProperties props) {
        return new Queue(props.rabbitmq().queue(), true);
    }

    /** "#" receives every event type (BookingCreated, SeatLocked, PaymentCompleted, ...). */
    @Bean
    public Binding auditBinding(Queue auditQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(auditQueue).to(eventsExchange).with("#");
    }
}
