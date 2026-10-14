package com.movieticket.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox: publishing an event only INSERTS a row into outbox_events, inside the caller's
 * database transaction. If the transaction rolls back the event disappears with it; if it commits the
 * event is guaranteed to exist and the relay will deliver it to RabbitMQ later (at-least-once).
 */
@Component
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final OutboxEventRepository outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DomainEventPublisher(OutboxEventRepository outbox, ObjectMapper mapper, Clock clock) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(EventType type, Long aggregateId, Map<String, Object> data) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", type.displayName());
        envelope.put("aggregateType", "Booking");
        envelope.put("aggregateId", aggregateId);
        envelope.put("occurredAt", now.toString());
        envelope.put("data", data);
        try {
            outbox.save(new OutboxEvent(type, aggregateId, mapper.writeValueAsString(envelope), now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise event " + type, e);
        }
        log.debug("Outbox <- {} (booking {})", type.displayName(), aggregateId);
    }
}
