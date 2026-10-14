package com.movieticket.saga;

import com.movieticket.booking.Booking;
import com.movieticket.booking.BookingDtos.BookingResponse;
import com.movieticket.booking.BookingDtos.BookingResult;
import com.movieticket.booking.BookingDtos.CreateBookingRequest;
import com.movieticket.booking.BookingRepository;
import com.movieticket.booking.BookingService;
import com.movieticket.booking.BookingStatus;
import com.movieticket.common.RequestHasher;
import com.movieticket.common.exception.ApiException;
import com.movieticket.config.AppProperties;
import com.movieticket.events.DomainEventPublisher;
import com.movieticket.events.EventData;
import com.movieticket.events.EventType;
import com.movieticket.notification.NotificationService;
import com.movieticket.notification.NotificationType;
import com.movieticket.payment.Payment;
import com.movieticket.payment.PaymentCommand;
import com.movieticket.payment.PaymentDtos.PaymentRequest;
import com.movieticket.payment.PaymentDtos.PaymentResult;
import com.movieticket.payment.PaymentMethod;
import com.movieticket.payment.PaymentRepository;
import com.movieticket.payment.PaymentService;
import com.movieticket.payment.PaymentService.PaymentOutcome;
import com.movieticket.payment.PaymentStatus;
import com.movieticket.seat.Seat;
import com.movieticket.seat.SeatRepository;
import com.movieticket.seat.SeatService;
import com.movieticket.seat.SeatStatus;
import com.movieticket.security.AuthenticatedUser;
import com.movieticket.show.Show;
import com.movieticket.show.ShowRepository;
import com.movieticket.user.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrator of the booking Saga. Each step is its own short MySQL transaction, and every step also
 * writes its progress to saga_transactions and its domain event to outbox_events.
 *
 * <pre>
 *  1 create PENDING booking + saga row        (idempotency key checked here)
 *  2 lock seat    AVAILABLE -> TEMPORARILY_LOCKED   (atomic UPDATE; loser gets HTTP 409)
 *  3 charge payment (simulated gateway, idempotent)
 *  4 confirm seat TEMPORARILY_LOCKED -> BOOKED      (only if the lock is still valid and ours)
 *    + booking CONFIRMED + notification
 *
 *  Compensation (any failure): booking CANCELLED, payment refunded if it was captured,
 *  seat released, saga COMPENSATED, failure notification, BookingCancelled event.
 * </pre>
 */
@Service
public class BookingSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BookingSagaOrchestrator.class);

    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;
    private final BookingRepository bookings;
    private final SagaRepository sagas;
    private final SeatService seatService;
    private final SeatRepository seatRepository;
    private final ShowRepository shows;
    private final UserRepository users;
    private final PaymentService paymentService;
    private final PaymentRepository payments;
    private final NotificationService notifications;
    private final DomainEventPublisher events;
    private final BookingService bookingService;
    private final AppProperties props;
    private final Clock clock;

    public BookingSagaOrchestrator(TransactionTemplate tx,
                                   @Qualifier("requiresNewTransactionTemplate") TransactionTemplate newTx,
                                   BookingRepository bookings, SagaRepository sagas, SeatService seatService,
                                   SeatRepository seatRepository, ShowRepository shows, UserRepository users,
                                   PaymentService paymentService, PaymentRepository payments,
                                   NotificationService notifications, DomainEventPublisher events,
                                   BookingService bookingService, AppProperties props, Clock clock) {
        this.tx = tx;
        this.newTx = newTx;
        this.bookings = bookings;
        this.sagas = sagas;
        this.seatService = seatService;
        this.seatRepository = seatRepository;
        this.shows = shows;
        this.users = users;
        this.paymentService = paymentService;
        this.payments = payments;
        this.notifications = notifications;
        this.events = events;
        this.bookingService = bookingService;
        this.props = props;
        this.clock = clock;
    }

    private record Created(Long bookingId, boolean replayed) {
    }

    private enum ConfirmOutcome {
        CONFIRMED, ALREADY_CONFIRMED, LOCK_LOST
    }

    // =====================================================================================
    //  Entry point 1: POST /api/bookings
    // =====================================================================================

    public BookingResult execute(AuthenticatedUser user, CreateBookingRequest req, String idempotencyKey) {
        PaymentMethod method = resolveMethod(req.paymentMethod());
        boolean autoPay = req.autoPay() == null || req.autoPay();
        String hash = RequestHasher.sha256Hex(user.id() + "|" + req.showId() + "|" + req.seatId() + "|" + autoPay + "|" + method);

        // ---- STEP 1: PENDING booking + saga row (or the stored result of an earlier identical request)
        Created created = createOrFindBooking(user, req, idempotencyKey, hash);
        if (created.replayed()) {
            return replay(created.bookingId());
        }
        Long bookingId = created.bookingId();

        // ---- STEP 2: lock the seat atomically
        boolean locked = lockSeat(bookingId, req.seatId(), autoPay);
        if (!locked && releaseExpiredSeat(req.seatId())) {
            locked = lockSeat(bookingId, req.seatId(), autoPay); // the old hold had expired; try once more
        }
        if (!locked) {
            cancelPending(bookingId, "SEAT_UNAVAILABLE", "Seat is already booked or held by another customer");
            throw ApiException.conflict("SEAT_UNAVAILABLE",
                    "Seat " + req.seatId() + " is already booked or being held by another customer");
        }

        if (!autoPay) {
            return new BookingResult(bookingService.responseFor(bookingId), false); // pay later via POST /api/payments
        }

        // ---- STEP 3: payment (idempotent; derived key so a retried booking can never charge twice)
        Booking booking = bookings.findById(bookingId).orElseThrow();
        PaymentOutcome outcome;
        try {
            outcome = paymentService.processPayment(new PaymentCommand(bookingId, booking.getTotalAmount(), method,
                    "pay-" + idempotencyKey));
        } catch (ApiException rejected) {
            cancelPending(bookingId, "PAYMENT_REJECTED", rejected.getMessage());
            throw rejected;
        }
        // any other exception leaves the saga IN_PROGRESS; SagaRecoveryJob finishes or rolls it back

        // ---- STEP 4: confirm, or compensate
        afterPayment(bookingId, outcome.payment(), method);
        return new BookingResult(bookingService.responseFor(bookingId), false);
    }

    private Created createOrFindBooking(AuthenticatedUser user, CreateBookingRequest req, String key, String hash) {
        try {
            return tx.execute(status -> createPending(user, req, key, hash));
        } catch (DataIntegrityViolationException duplicateKey) {
            // Two simultaneous requests used the same key; the unique index let only one INSERT through.
            return tx.execute(status -> {
                Booking winner = bookings.findByIdempotencyKey(key).orElseThrow(() -> duplicateKey);
                requireSameRequest(winner, user, hash);
                return new Created(winner.getId(), true);
            });
        }
    }

    private Created createPending(AuthenticatedUser user, CreateBookingRequest req, String key, String hash) {
        Optional<Booking> existing = bookings.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            requireSameRequest(existing.get(), user, hash);
            return new Created(existing.get().getId(), true);
        }
        if (!users.existsById(user.id())) {
            throw ApiException.notFound("USER_NOT_FOUND", "User " + user.id() + " does not exist");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Show show = shows.findById(req.showId())
                .orElseThrow(() -> ApiException.notFound("SHOW_NOT_FOUND", "Show " + req.showId() + " does not exist"));
        if (!show.getShowTime().isAfter(now)) {
            throw ApiException.badRequest("SHOW_ALREADY_STARTED", "Show " + show.getId() + " has already started");
        }
        seatService.requireSeatOfShow(req.seatId(), req.showId()); // 404 / 400 for wrong seat or show

        Booking booking = bookings.saveAndFlush(new Booking(user.id(), show.getId(), req.seatId(), show.getPrice(),
                key, hash, now));
        sagas.save(new SagaTransaction(booking.getId(), SagaStep.BOOKING_CREATED, SagaStatus.IN_PROGRESS, now));
        events.publish(EventType.BOOKING_CREATED, booking.getId(),
                EventData.of("bookingId", booking.getId(), "userId", user.id(), "showId", show.getId(),
                        "seatId", req.seatId(), "amount", show.getPrice()));
        return new Created(booking.getId(), false);
    }

    private void requireSameRequest(Booking existing, AuthenticatedUser user, String hash) {
        if (!existing.getUserId().equals(user.id()) || !existing.getRequestHash().equals(hash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request");
        }
    }

    /** Returns the stored result; if the original request is still running, waits briefly for it to finish. */
    private BookingResult replay(Long bookingId) {
        long deadline = System.currentTimeMillis() + props.idempotency().waitMs();
        while (System.currentTimeMillis() < deadline) {
            Optional<SagaTransaction> saga = sagas.findByBookingId(bookingId);
            if (saga.isEmpty() || saga.get().getStatus() != SagaStatus.IN_PROGRESS) {
                break;
            }
            sleep(50);
        }
        return new BookingResult(bookingService.responseFor(bookingId), true);
    }

    // =====================================================================================
    //  Entry point 2: POST /api/payments  (booking created with autoPay=false)
    // =====================================================================================

    public PaymentResult payForBooking(AuthenticatedUser user, PaymentRequest req) {
        Booking booking = bookings.findById(req.bookingId())
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking " + req.bookingId() + " does not exist"));
        bookingService.requireOwnerOrAdmin(booking, user);
        PaymentMethod method = resolveMethod(req.paymentMethod());

        PaymentOutcome outcome = paymentService.processPayment(
                new PaymentCommand(booking.getId(), req.amount(), method, req.idempotencyKey()));
        afterPayment(booking.getId(), outcome.payment(), method); // idempotent: safe for replays too

        Payment latest = payments.findById(outcome.payment().getId()).orElseThrow();
        return new PaymentResult(paymentService.toResponse(latest), outcome.replayed());
    }

    // =====================================================================================
    //  Steps 2-4 helpers
    // =====================================================================================

    private boolean lockSeat(Long bookingId, Long seatId, boolean autoPay) {
        Boolean ok = retryOnLock(() -> tx.execute(status -> {
            LocalDateTime until = LocalDateTime.now(clock).plusMinutes(props.seatLock().durationMinutes());
            if (!seatService.lock(seatId, bookingId, until)) {
                return false;
            }
            updateSaga(bookingId, SagaStep.SEAT_LOCKED, autoPay ? SagaStatus.IN_PROGRESS : SagaStatus.AWAITING_PAYMENT, null);
            events.publish(EventType.SEAT_LOCKED, bookingId,
                    EventData.of("bookingId", bookingId, "seatId", seatId, "lockedUntil", until));
            return true;
        }));
        return Boolean.TRUE.equals(ok);
    }

    private void afterPayment(Long bookingId, Payment payment, PaymentMethod method) {
        switch (payment.getStatus()) {
            case COMPLETED -> finalizeCompletedPayment(bookingId, payment, method);
            case FAILED -> {
                String reason = payment.getFailureReason() == null ? "Payment failed" : payment.getFailureReason();
                String code = reason.startsWith("TIMEOUT") ? "PAYMENT_TIMEOUT" : "PAYMENT_FAILED";
                cancelPending(bookingId, code, reason);
            }
            case REFUNDED, PENDING -> {
                // REFUNDED: already compensated.  PENDING: still in flight elsewhere; nothing to do yet.
            }
        }
    }

    /** Payment captured: confirm the seat and booking, or compensate (refund) if the hold is gone. */
    private void finalizeCompletedPayment(Long bookingId, Payment payment, PaymentMethod method) {
        if (method == PaymentMethod.TEST_CAPTURE_THEN_FAIL && props.payment().simulator().faultInjectionEnabled()) {
            // Simulated failure AFTER the money was captured -> proves the refund compensation works.
            cancelPending(bookingId, "POST_PAYMENT_FAILURE",
                    "Simulated failure after payment capture; payment was refunded");
            return;
        }
        ConfirmOutcome outcome = retryOnLock(() -> tx.execute(status -> confirmBooking(bookingId)));
        if (outcome == ConfirmOutcome.LOCK_LOST) {
            cancelPending(bookingId, "SEAT_LOCK_EXPIRED",
                    "Seat hold expired before payment completed; payment was refunded");
        }
    }

    private ConfirmOutcome confirmBooking(Long bookingId) {
        Booking b = bookings.findByIdForUpdate(bookingId).orElseThrow();
        if (b.getStatus() == BookingStatus.CONFIRMED) {
            return ConfirmOutcome.ALREADY_CONFIRMED;
        }
        if (b.getStatus() == BookingStatus.CANCELLED) {
            return ConfirmOutcome.LOCK_LOST; // e.g. the expiry job cancelled it while the payment was in flight
        }
        updateSaga(bookingId, SagaStep.PAYMENT_PROCESSED, SagaStatus.IN_PROGRESS, null);
        // Atomic: succeeds only if the seat is still locked by THIS booking and the lock has not expired.
        if (!seatService.confirm(b.getSeatId(), bookingId)) {
            return ConfirmOutcome.LOCK_LOST;
        }
        int moved = bookings.transition(bookingId, BookingStatus.PENDING.name(), BookingStatus.CONFIRMED.name(), null,
                LocalDateTime.now(clock));
        if (moved != 1) {
            throw new IllegalStateException("Booking " + bookingId + " left PENDING while its seat lock was held");
        }
        updateSaga(bookingId, SagaStep.BOOKING_CONFIRMED, SagaStatus.COMPLETED, null);
        events.publish(EventType.BOOKING_CONFIRMED, bookingId,
                EventData.of("bookingId", bookingId, "userId", b.getUserId(), "showId", b.getShowId(),
                        "seatId", b.getSeatId(), "amount", b.getTotalAmount()));
        notifications.send(b.getUserId(), NotificationType.BOOKING_CONFIRMED,
                "Your booking #" + bookingId + " is confirmed. Amount paid: " + b.getTotalAmount() + " (simulated payment).");
        log.info("Booking {} CONFIRMED", bookingId);
        return ConfirmOutcome.CONFIRMED;
    }

    // =====================================================================================
    //  Compensation
    // =====================================================================================

    /** Cancels a PENDING booking and undoes everything it did. Safe to call repeatedly. */
    private boolean cancelPending(Long bookingId, String reasonCode, String message) {
        return Boolean.TRUE.equals(retryOnLock(() -> tx.execute(status -> cancelPendingInTx(bookingId, reasonCode, message))));
    }

    /** Must run inside a transaction. */
    private boolean cancelPendingInTx(Long bookingId, String reasonCode, String message) {
        String reason = trim(reasonCode + ": " + message);
        int moved = bookings.transition(bookingId, BookingStatus.PENDING.name(), BookingStatus.CANCELLED.name(), reason,
                LocalDateTime.now(clock));
        Booking b = bookings.findById(bookingId).orElseThrow();
        if (b.getStatus() != BookingStatus.CANCELLED) {
            return false; // it is CONFIRMED: somebody else finished it, nothing to compensate
        }

        // Compensation 1: give captured money back (also covers a payment that completed AFTER the cancel).
        boolean refunded = refundCapturedPayments(b, reasonCode);
        if (moved == 0) {
            if (refunded) {
                notifications.send(b.getUserId(), NotificationType.PAYMENT_REFUNDED,
                        "Booking #" + bookingId + " was cancelled earlier; your payment has been refunded.");
            }
            return refunded;
        }

        // Compensation 2: free the seat (only if this booking still holds the lock).
        if (seatService.release(b.getSeatId(), bookingId)) {
            events.publish(EventType.SEAT_RELEASED, bookingId,
                    EventData.of("bookingId", bookingId, "seatId", b.getSeatId(), "reason", reasonCode));
        }

        // Compensation 3: record + notify.
        updateSaga(bookingId, SagaStep.COMPENSATION, SagaStatus.COMPENSATED, reason);
        events.publish(EventType.BOOKING_CANCELLED, bookingId,
                EventData.of("bookingId", bookingId, "userId", b.getUserId(), "reason", reason, "refunded", refunded));
        boolean byUser = "CANCELLED_BY_USER".equals(reasonCode);
        notifications.send(b.getUserId(), byUser ? NotificationType.BOOKING_CANCELLED : NotificationType.BOOKING_FAILED,
                "Booking #" + bookingId + (byUser ? " was cancelled." : " could not be completed: " + message)
                        + (refunded ? " Your payment has been refunded." : ""));
        log.info("Booking {} CANCELLED ({})", bookingId, reasonCode);
        return true;
    }

    private boolean refundCapturedPayments(Booking booking, String reasonCode) {
        boolean any = false;
        for (Payment p : payments.findByBookingIdAndStatus(booking.getId(), PaymentStatus.COMPLETED)) {
            any |= paymentService.refund(p.getId(), reasonCode);
        }
        return any;
    }

    /** User-initiated cancellation of a PENDING or CONFIRMED booking (the latter is refunded and the seat freed). */
    public BookingResponse cancelBooking(AuthenticatedUser user, Long bookingId) {
        Booking b = bookings.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking " + bookingId + " does not exist"));
        bookingService.requireOwnerOrAdmin(b, user);
        switch (b.getStatus()) {
            case CANCELLED -> { /* already cancelled: idempotent */ }
            case PENDING -> cancelPending(bookingId, "CANCELLED_BY_USER", "Cancelled by customer");
            case CONFIRMED -> tx.executeWithoutResult(status -> cancelConfirmed(bookingId));
        }
        return bookingService.responseFor(bookingId);
    }

    private void cancelConfirmed(Long bookingId) {
        Booking b = bookings.findByIdForUpdate(bookingId).orElseThrow();
        if (b.getStatus() != BookingStatus.CONFIRMED) {
            return; // changed concurrently
        }
        Show show = shows.findById(b.getShowId()).orElseThrow();
        if (!show.getShowTime().isAfter(LocalDateTime.now(clock))) {
            throw ApiException.conflict("SHOW_ALREADY_STARTED", "A booking cannot be cancelled after the show has started");
        }
        bookings.transition(bookingId, BookingStatus.CONFIRMED.name(), BookingStatus.CANCELLED.name(),
                "CANCELLED_BY_USER: Cancelled by customer", LocalDateTime.now(clock));
        if (seatService.releaseBooked(b.getSeatId(), bookingId)) {
            events.publish(EventType.SEAT_RELEASED, bookingId,
                    EventData.of("bookingId", bookingId, "seatId", b.getSeatId(), "reason", "CANCELLED_BY_USER"));
        }
        boolean refunded = refundCapturedPayments(b, "CANCELLED_BY_USER");
        updateSaga(bookingId, SagaStep.COMPENSATION, SagaStatus.COMPENSATED, "CANCELLED_BY_USER");
        events.publish(EventType.BOOKING_CANCELLED, bookingId,
                EventData.of("bookingId", bookingId, "userId", b.getUserId(), "reason", "CANCELLED_BY_USER", "refunded", refunded));
        notifications.send(b.getUserId(), NotificationType.BOOKING_CANCELLED,
                "Booking #" + bookingId + " was cancelled." + (refunded ? " Your payment has been refunded." : ""));
    }

    // =====================================================================================
    //  Timeout handling: expired seat locks
    // =====================================================================================

    /** Called by SeatLockExpiryJob. @return how many seats were released. */
    public int releaseExpiredLocks() {
        int released = 0;
        for (Long seatId : seatService.findExpiredLockSeatIds(LocalDateTime.now(clock))) {
            try {
                if (releaseExpiredSeat(seatId)) {
                    released++;
                }
            } catch (RuntimeException ex) {
                log.warn("Could not release expired seat {}: {}", seatId, ex.getMessage());
            }
        }
        return released;
    }

    /**
     * Releases ONE seat if (and only if) its lock has expired, and cancels the booking that held it.
     * The conditional UPDATE in SeatRepository makes this safe against a payment confirming at the same instant.
     */
    public boolean releaseExpiredSeat(Long seatId) {
        return Boolean.TRUE.equals(newTx.execute(status -> {
            Seat seat = seatRepository.findById(seatId).orElse(null);
            LocalDateTime now = LocalDateTime.now(clock);
            if (seat == null || seat.getStatus() != SeatStatus.TEMPORARILY_LOCKED || seat.getLockedUntil() == null
                    || seat.getLockedUntil().isAfter(now)) {
                return false;
            }
            Long holder = seat.getLockedByBookingId();
            if (holder != null) {
                bookings.findByIdForUpdate(holder); // same lock order as every other path: booking first, then seat
            }
            if (!seatService.releaseExpired(seatId, holder)) {
                return false; // someone confirmed or released it first
            }
            events.publish(EventType.SEAT_RELEASED, holder != null ? holder : seatId,
                    EventData.of("bookingId", holder, "seatId", seatId, "reason", "LOCK_EXPIRED"));
            if (holder != null) {
                cancelPendingInTx(holder, "LOCK_EXPIRED", "Seat hold expired before payment was completed");
            }
            return true;
        }));
    }

    // =====================================================================================
    //  Recovery after a crash / restart
    // =====================================================================================

    /** Called by SagaRecoveryJob. Finishes or rolls back sagas that were left IN_PROGRESS. @return sagas handled. */
    public int recoverStuckSagas() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusSeconds(props.saga().recoveryAfterSeconds());
        int handled = 0;
        for (SagaTransaction saga : sagas.findByStatusAndUpdatedAtBefore(SagaStatus.IN_PROGRESS, cutoff)) {
            try {
                recoverOne(saga.getBookingId());
                handled++;
            } catch (RuntimeException ex) {
                log.warn("Recovery of saga for booking {} failed: {}", saga.getBookingId(), ex.getMessage());
            }
        }
        return handled;
    }

    private void recoverOne(Long bookingId) {
        Booking b = bookings.findById(bookingId).orElse(null);
        if (b == null) {
            return;
        }
        log.warn("Recovering interrupted saga for booking {} (status {})", bookingId, b.getStatus());
        if (b.getStatus() == BookingStatus.CONFIRMED) {
            tx.executeWithoutResult(s -> updateSaga(bookingId, SagaStep.BOOKING_CONFIRMED, SagaStatus.COMPLETED, null));
            return;
        }
        if (b.getStatus() == BookingStatus.CANCELLED) {
            cancelPending(bookingId, "SAGA_RECOVERY", "Booking was already cancelled"); // refunds a late capture
            tx.executeWithoutResult(s -> updateSaga(bookingId, SagaStep.COMPENSATION, SagaStatus.COMPENSATED,
                    b.getFailureReason()));
            return;
        }
        List<Payment> attempts = payments.findByBookingIdOrderByIdDesc(bookingId);
        Optional<Payment> captured = attempts.stream().filter(p -> p.getStatus() == PaymentStatus.COMPLETED).findFirst();
        if (captured.isPresent()) {
            // crashed after the money was captured: finish the booking, or refund if the hold is gone
            finalizeCompletedPayment(bookingId, captured.get(), null);
            return;
        }
        for (Payment p : attempts) {
            if (p.getStatus() == PaymentStatus.PENDING) {
                tx.executeWithoutResult(s -> paymentService.failIfPending(p.getId(),
                        "RECOVERY: gateway outcome unknown after interruption"));
            }
        }
        cancelPending(bookingId, "SAGA_INTERRUPTED", "Booking was interrupted and has been rolled back");
    }

    // =====================================================================================
    //  small helpers
    // =====================================================================================

    /** Must run inside a transaction. */
    private void updateSaga(Long bookingId, SagaStep step, SagaStatus status, String reason) {
        SagaTransaction saga = sagas.findByBookingId(bookingId)
                .orElseThrow(() -> new IllegalStateException("No saga row for booking " + bookingId));
        saga.advance(step, status, reason, LocalDateTime.now(clock));
        sagas.save(saga);
    }

    private PaymentMethod resolveMethod(PaymentMethod requested) {
        if (requested != null) {
            return requested;
        }
        return PaymentMethod.valueOf(props.payment().simulator().defaultMethod());
    }

    /** MySQL may abort one of two transactions that deadlock; the aborted one is simply safe to run again. */
    private <T> T retryOnLock(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (PessimisticLockingFailureException ex) {
                if (attempt >= 3) {
                    throw ex;
                }
                log.warn("Lock conflict (attempt {}), retrying: {}", attempt, ex.getMessage());
                sleep(20L * attempt);
            }
        }
    }

    private static String trim(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
