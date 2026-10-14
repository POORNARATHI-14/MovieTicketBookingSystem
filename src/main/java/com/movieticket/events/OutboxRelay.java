package com.movieticket.events;

import com.movieticket.config.AppProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls outbox_events and pushes PENDING rows to RabbitMQ. Delivery is at-least-once: a crash after the
 * broker accepts a message but before the row is marked PUBLISHED re-sends it, so consumers must be
 * idempotent (every message carries a unique eventId).
 */
@Component
@ConditionalOnProperty(name = "app.rabbitmq.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final EventMessagePublisher publisher;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository outbox, EventMessagePublisher publisher, TransactionTemplate tx,
                       AppProperties props, Clock clock) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms}")
    public void scheduledRelay() {
        try {
            relayPending();
        } catch (RuntimeException ex) {
            log.warn("Outbox relay run failed: {}", ex.getMessage());
        }
    }

    /** @return number of events delivered in this run. */
    public int relayPending() {
        Integer delivered = tx.execute(status -> {
            List<OutboxEvent> batch = outbox.lockPending(props.outbox().batchSize());
            int ok = 0;
            for (OutboxEvent event : batch) {
                try {
                    publisher.publish(event);
                    event.markPublished(LocalDateTime.now(clock));
                    ok++;
                } catch (Exception ex) {
                    event.markFailedAttempt(ex.getMessage());
                    log.warn("Could not publish outbox event {} ({}): {}", event.getId(), event.getEventType(), ex.getMessage());
                    break; // keep ordering: stop at the first failure, retry on the next poll
                }
            }
            return ok;
        });
        return delivered == null ? 0 : delivered;
    }
}
