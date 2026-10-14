package com.movieticket.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class AppConfig {

    /** Single time source for the whole app (lock expiry, timestamps). Tests could replace it. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /** Runs a block in the caller's transaction, or starts one. Used by the Saga steps. */
    @Bean
    @Primary
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    /** Always starts an independent transaction (one per expired seat, so one failure cannot block others). */
    @Bean
    public TransactionTemplate requiresNewTransactionTemplate(PlatformTransactionManager txManager) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Configuration
    @EnableScheduling
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
