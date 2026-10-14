package com.movieticket.booking;

import com.movieticket.payment.PaymentMethod;
import com.movieticket.saga.SagaStatus;
import com.movieticket.saga.SagaStep;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class BookingDtos {

    private BookingDtos() {
    }

    /**
     * @param paymentMethod simulator "test card" (defaults to app.payment.simulator.default-method)
     * @param autoPay       true (default): the Saga also charges the simulated payment in the same call.
     *                      false: only reserve the seat; pay later with POST /api/payments before the lock expires.
     */
    public record CreateBookingRequest(
            @NotNull(message = "showId is required") Long showId,
            @NotNull(message = "seatId is required") Long seatId,
            PaymentMethod paymentMethod,
            Boolean autoPay) {
    }

    public record BookingResponse(Long id, Long userId, Long showId, Long seatId, String seatNumber, BookingStatus status,
                                  BigDecimal totalAmount, String idempotencyKey, String failureReason,
                                  SagaStep sagaStep, SagaStatus sagaStatus, LocalDateTime lockExpiresAt,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** Outcome of the booking Saga; {@code replayed} is true when an earlier result for the same idempotency key was returned. */
    public record BookingResult(BookingResponse booking, boolean replayed) {
    }
}
