package com.movieticket.payment;

import com.movieticket.booking.BookingStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** Pays for a booking that was created with autoPay=false. */
    public record PaymentRequest(
            @NotNull(message = "bookingId is required") Long bookingId,
            @NotNull(message = "amount is required") @DecimalMin(value = "0.01", message = "amount must be greater than 0")
            @Digits(integer = 8, fraction = 2, message = "amount must have at most 2 decimal places") BigDecimal amount,
            PaymentMethod paymentMethod,
            @NotBlank(message = "idempotencyKey is required")
            @Size(min = 8, max = 64, message = "idempotencyKey must be 8-64 characters")
            @Pattern(regexp = "[A-Za-z0-9_-]+", message = "idempotencyKey may only contain letters, digits, '-' and '_'") String idempotencyKey) {
    }

    public record PaymentResponse(Long id, Long bookingId, BigDecimal amount, PaymentStatus status, PaymentMethod method,
                                  String idempotencyKey, String gatewayReference, String failureReason,
                                  BookingStatus bookingStatus, LocalDateTime createdAt) {
    }

    /** {@code replayed} is true when the original result for the same idempotency key was returned. */
    public record PaymentResult(PaymentResponse payment, boolean replayed) {
    }
}
