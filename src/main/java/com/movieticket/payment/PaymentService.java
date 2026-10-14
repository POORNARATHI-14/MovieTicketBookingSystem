package com.movieticket.payment;

import com.movieticket.booking.Booking;
import com.movieticket.booking.BookingRepository;
import com.movieticket.booking.BookingStatus;
import com.movieticket.common.RequestHasher;
import com.movieticket.common.exception.ApiException;
import com.movieticket.config.AppProperties;
import com.movieticket.events.DomainEventPublisher;
import com.movieticket.events.EventData;
import com.movieticket.events.EventType;
import com.movieticket.payment.PaymentDtos.PaymentResponse;
import com.movieticket.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent payment processing against the (simulated) gateway.
 *
 * <p>The charge is made in three separate steps so that nothing is ever charged twice:
 * <ol>
 *   <li>one transaction locks the booking row, checks idempotency + amount and inserts a PENDING payment;</li>
 *   <li>the gateway is called OUTSIDE any database transaction;</li>
 *   <li>a second transaction settles the payment PENDING -> COMPLETED/FAILED (compare-and-set).</li>
 * </ol>
 * The unique idempotency_key column is the final arbiter for simultaneous duplicates.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final BookingRepository bookings;
    private final PaymentGateway gateway;
    private final DomainEventPublisher events;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, BookingRepository bookings, PaymentGateway gateway,
                          DomainEventPublisher events, TransactionTemplate tx, AppProperties props, Clock clock) {
        this.payments = payments;
        this.bookings = bookings;
        this.gateway = gateway;
        this.events = events;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    /** Result of {@link #processPayment}: the payment as it stands, and whether it was an idempotent replay. */
    public record PaymentOutcome(Payment payment, boolean replayed) {
    }

    private record Initiation(Payment payment, boolean replayed) {
    }

    public PaymentOutcome processPayment(PaymentCommand cmd) {
        String hash = fingerprint(cmd);

        Initiation initiation;
        try {
            initiation = tx.execute(status -> initiate(cmd, hash));
        } catch (DataIntegrityViolationException duplicate) {
            // A simultaneous request with the same idempotency key inserted first; the unique index rejected us.
            initiation = tx.execute(status -> replayOrReject(payments.findByIdempotencyKey(cmd.idempotencyKey())
                    .orElseThrow(() -> ApiException.conflict("PAYMENT_CONFLICT",
                            "Payment could not be recorded; please retry")), cmd, hash));
        }

        if (initiation.replayed()) {
            return new PaymentOutcome(awaitSettled(initiation.payment().getId()), true);
        }

        Payment pending = initiation.payment();
        GatewayResult result = chargeGateway(cmd);
        Payment settled = tx.execute(status -> settle(pending.getId(), result));
        return new PaymentOutcome(settled, false);
    }

    private Initiation initiate(PaymentCommand cmd, String hash) {
        // Row lock: payments for the same booking are processed one at a time.
        Booking booking = bookings.findByIdForUpdate(cmd.bookingId())
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking " + cmd.bookingId() + " does not exist"));

        // Idempotency check comes first (and AFTER the lock) so a replay of an already-successful payment
        // is answered with the original result even though the booking is no longer PENDING.
        var existing = payments.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrReject(existing.get(), cmd, hash);
        }

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw ApiException.conflict("BOOKING_NOT_PAYABLE",
                    "Booking " + booking.getId() + " is " + booking.getStatus() + " and cannot be paid");
        }
        if (cmd.amount().compareTo(booking.getTotalAmount()) != 0) {
            throw ApiException.badRequest("INVALID_AMOUNT",
                    "Amount " + cmd.amount() + " does not match the booking total " + booking.getTotalAmount());
        }
        if (payments.existsByBookingIdAndStatusIn(booking.getId(), List.of(PaymentStatus.PENDING, PaymentStatus.COMPLETED))) {
            throw ApiException.conflict("PAYMENT_ALREADY_IN_PROGRESS",
                    "Booking " + booking.getId() + " already has a payment in progress or completed");
        }
        Payment payment = payments.saveAndFlush(new Payment(booking.getId(), cmd.amount(), cmd.method(),
                cmd.idempotencyKey(), hash, LocalDateTime.now(clock)));
        return new Initiation(payment, false);
    }

    private Initiation replayOrReject(Payment existing, PaymentCommand cmd, String hash) {
        if (!existing.getBookingId().equals(cmd.bookingId()) || !existing.getRequestHash().equals(hash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This idempotency key was already used for a different payment request");
        }
        return new Initiation(existing, true);
    }

    private GatewayResult chargeGateway(PaymentCommand cmd) {
        try {
            return gateway.charge(cmd.bookingId(), cmd.amount(), cmd.method());
        } catch (RuntimeException ex) {
            log.warn("Gateway error for booking {}: {}", cmd.bookingId(), ex.getMessage());
            return new GatewayResult(GatewayResult.Outcome.TIMEOUT, null, "Gateway error: " + ex.getMessage());
        }
    }

    private Payment settle(Long paymentId, GatewayResult result) {
        boolean ok = result.isSuccess();
        String reason = ok ? null : result.outcome() + ": " + result.message();
        int moved = payments.transition(paymentId, PaymentStatus.PENDING.name(),
                (ok ? PaymentStatus.COMPLETED : PaymentStatus.FAILED).name(), result.reference(), reason,
                LocalDateTime.now(clock));
        Payment payment = payments.findById(paymentId).orElseThrow();
        if (moved == 1) {
            events.publish(ok ? EventType.PAYMENT_COMPLETED : EventType.PAYMENT_FAILED, payment.getBookingId(),
                    EventData.of("bookingId", payment.getBookingId(), "paymentId", payment.getId(),
                            "amount", payment.getAmount(), "method", payment.getMethod().name(), "reason", reason,
                            "simulated", true));
        }
        return payment;
    }

    /** A concurrent duplicate may arrive while the first request is still talking to the gateway. */
    private Payment awaitSettled(Long paymentId) {
        long deadline = System.currentTimeMillis() + props.idempotency().waitMs();
        Payment p = payments.findById(paymentId).orElseThrow();
        while (p.getStatus() == PaymentStatus.PENDING && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            p = payments.findById(paymentId).orElseThrow();
        }
        return p;
    }

    /**
     * Compensation: returns the money for a COMPLETED payment (simulated). Joins the caller's transaction so the
     * refund and the booking cancellation commit together.
     *
     * @return true if this call performed the refund
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean refund(Long paymentId, String reason) {
        Payment p = payments.findById(paymentId).orElseThrow();
        if (p.getStatus() != PaymentStatus.COMPLETED) {
            return false;
        }
        GatewayResult result = gateway.refund(p.getGatewayReference(), p.getAmount());
        if (!result.isSuccess()) {
            // roll the whole compensation back; the Saga recovery job will try again
            throw new IllegalStateException("Refund of payment " + paymentId + " was rejected: " + result.message());
        }
        int moved = payments.transition(paymentId, PaymentStatus.COMPLETED.name(), PaymentStatus.REFUNDED.name(), null,
                "Refunded (" + reason + ") ref " + result.reference(), LocalDateTime.now(clock));
        if (moved == 1) {
            events.publish(EventType.PAYMENT_REFUNDED, p.getBookingId(),
                    EventData.of("bookingId", p.getBookingId(), "paymentId", paymentId, "amount", p.getAmount(),
                            "reason", reason, "simulated", true));
        }
        return moved == 1;
    }

    /** Recovery: a payment left PENDING by a crash has an unknown outcome; the simulator treats it as not charged. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void failIfPending(Long paymentId, String reason) {
        int moved = payments.transition(paymentId, PaymentStatus.PENDING.name(), PaymentStatus.FAILED.name(), null,
                reason, LocalDateTime.now(clock));
        if (moved == 1) {
            Payment p = payments.findById(paymentId).orElseThrow();
            events.publish(EventType.PAYMENT_FAILED, p.getBookingId(),
                    EventData.of("bookingId", p.getBookingId(), "paymentId", paymentId, "reason", reason, "simulated", true));
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(Long paymentId, AuthenticatedUser caller) {
        Payment p = payments.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment " + paymentId + " does not exist"));
        Booking booking = bookings.findById(p.getBookingId()).orElseThrow();
        if (!caller.isAdmin() && !caller.id().equals(booking.getUserId())) {
            throw ApiException.forbidden("ACCESS_DENIED", "This payment belongs to another user");
        }
        return toResponse(p);
    }

    @Transactional(readOnly = true)
    public PaymentResponse toResponse(Payment p) {
        BookingStatus bookingStatus = bookings.findById(p.getBookingId()).map(Booking::getStatus).orElse(null);
        return new PaymentResponse(p.getId(), p.getBookingId(), p.getAmount(), p.getStatus(), p.getMethod(),
                p.getIdempotencyKey(), p.getGatewayReference(), p.getFailureReason(), bookingStatus, p.getCreatedAt());
    }

    private static String fingerprint(PaymentCommand cmd) {
        BigDecimal amount = cmd.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        return RequestHasher.sha256Hex(cmd.bookingId() + "|" + amount.toPlainString() + "|" + cmd.method());
    }
}
