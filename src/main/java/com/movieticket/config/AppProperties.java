package com.movieticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of every {@code app.*} property in application.properties. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Admin admin,
        SeatLock seatLock,
        Scheduling scheduling,
        Saga saga,
        Idempotency idempotency,
        Payment payment,
        Rabbitmq rabbitmq,
        Outbox outbox) {

    public record Jwt(String secret, int expirationMinutes) {}

    public record Admin(String name, String email, String password) {}

    public record SeatLock(int durationMinutes, long expiryScanMs) {}

    public record Scheduling(boolean enabled) {}

    public record Saga(long recoveryScanMs, long recoveryAfterSeconds) {}

    public record Idempotency(long waitMs) {}

    public record Payment(Simulator simulator) {}

    public record Simulator(String defaultMethod, double successRate, long latencyMs, boolean faultInjectionEnabled) {}

    public record Rabbitmq(boolean enabled, String exchange, String queue) {}

    public record Outbox(long pollMs, int batchSize) {}
}
