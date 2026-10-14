package com.movieticket.events;

/** Sends one outbox event to the message broker. Throws if the broker did not accept it. */
public interface EventMessagePublisher {

    void publish(OutboxEvent event) throws Exception;
}
