package com.movieticket.saga;

/** The last Saga step that completed (or COMPENSATION once the rollback ran). */
public enum SagaStep {
    BOOKING_CREATED,
    SEAT_LOCKED,
    PAYMENT_PROCESSED,
    BOOKING_CONFIRMED,
    COMPENSATION
}
