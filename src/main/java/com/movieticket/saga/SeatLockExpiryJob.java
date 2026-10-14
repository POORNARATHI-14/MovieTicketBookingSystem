package com.movieticket.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically releases seat locks whose time has run out (default hold: 5 minutes). */
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SeatLockExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(SeatLockExpiryJob.class);

    private final BookingSagaOrchestrator saga;

    public SeatLockExpiryJob(BookingSagaOrchestrator saga) {
        this.saga = saga;
    }

    @Scheduled(fixedDelayString = "${app.seat-lock.expiry-scan-ms}")
    public void releaseExpiredLocks() {
        try {
            int released = saga.releaseExpiredLocks();
            if (released > 0) {
                log.info("Released {} expired seat lock(s)", released);
            }
        } catch (RuntimeException ex) {
            log.warn("Seat lock expiry run failed: {}", ex.getMessage());
        }
    }
}
