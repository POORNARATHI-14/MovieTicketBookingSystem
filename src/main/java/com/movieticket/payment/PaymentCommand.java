package com.movieticket.payment;

import java.math.BigDecimal;

/** Internal request to charge a booking. The idempotency key makes the charge safe to retry. */
public record PaymentCommand(Long bookingId, BigDecimal amount, PaymentMethod method, String idempotencyKey) {
}
