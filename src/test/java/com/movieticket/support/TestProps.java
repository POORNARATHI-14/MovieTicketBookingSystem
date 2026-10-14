package com.movieticket.support;

import com.movieticket.config.AppProperties;

/** Builds an AppProperties for plain unit tests (no Spring context). */
public final class TestProps {

    private TestProps() {
    }

    public static AppProperties with(double successRate, long latencyMs, boolean faultInjection) {
        return new AppProperties(
                new AppProperties.Jwt("unit-test-secret-unit-test-secret-1234", 60),
                new AppProperties.Admin("Admin", "admin@test.local", ""),
                new AppProperties.SeatLock(5, 5000),
                new AppProperties.Scheduling(false),
                new AppProperties.Saga(30000, 60),
                new AppProperties.Idempotency(1000),
                new AppProperties.Payment(new AppProperties.Simulator("TEST_SUCCESS", successRate, latencyMs, faultInjection)),
                new AppProperties.Rabbitmq(false, "ex", "q"),
                new AppProperties.Outbox(1000, 10));
    }
}
