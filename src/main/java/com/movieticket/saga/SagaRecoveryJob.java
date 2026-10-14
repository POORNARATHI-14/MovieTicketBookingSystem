package com.movieticket.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Picks up sagas that were interrupted (e.g. application killed mid-booking) and completes or rolls them back. */
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SagaRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryJob.class);

    private final BookingSagaOrchestrator saga;

    public SagaRecoveryJob(BookingSagaOrchestrator saga) {
        this.saga = saga;
    }

    @Scheduled(fixedDelayString = "${app.saga.recovery-scan-ms}", initialDelayString = "${app.saga.recovery-scan-ms}")
    public void recover() {
        try {
            int handled = saga.recoverStuckSagas();
            if (handled > 0) {
                log.info("Recovered {} interrupted saga(s)", handled);
            }
        } catch (RuntimeException ex) {
            log.warn("Saga recovery run failed: {}", ex.getMessage());
        }
    }
}
