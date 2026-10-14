package com.movieticket.events;

/** Domain events published through the transactional outbox. */
public enum EventType {
    BOOKING_CREATED("BookingCreated", "booking.created"),
    SEAT_LOCKED("SeatLocked", "seat.locked"),
    PAYMENT_COMPLETED("PaymentCompleted", "payment.completed"),
    PAYMENT_FAILED("PaymentFailed", "payment.failed"),
    PAYMENT_REFUNDED("PaymentRefunded", "payment.refunded"),
    SEAT_RELEASED("SeatReleased", "seat.released"),
    BOOKING_CONFIRMED("BookingConfirmed", "booking.confirmed"),
    BOOKING_CANCELLED("BookingCancelled", "booking.cancelled");

    private final String displayName;
    private final String routingKey;

    EventType(String displayName, String routingKey) {
        this.displayName = displayName;
        this.routingKey = routingKey;
    }

    public String displayName() {
        return displayName;
    }

    public String routingKey() {
        return routingKey;
    }
}
