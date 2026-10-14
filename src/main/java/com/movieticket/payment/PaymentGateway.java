package com.movieticket.payment;

import java.math.BigDecimal;

/**
 * Boundary to the outside payment provider. The only implementation is {@link SimulatedPaymentGateway};
 * a real provider (Razorpay, Stripe, ...) would implement this same interface.
 */
public interface PaymentGateway {

    GatewayResult charge(Long bookingId, BigDecimal amount, PaymentMethod method);

    GatewayResult refund(String gatewayReference, BigDecimal amount);
}
