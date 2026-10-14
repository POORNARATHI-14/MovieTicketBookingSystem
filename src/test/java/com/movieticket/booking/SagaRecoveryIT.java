package com.movieticket.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movieticket.payment.PaymentStatus;
import com.movieticket.saga.SagaStatus;
import com.movieticket.support.AbstractIntegrationTest;
import com.movieticket.user.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Simulates the application dying in the middle of a Saga and verifies the recovery job cleans up. */
class SagaRecoveryIT extends AbstractIntegrationTest {

    private long heldBooking(User user, Fixture f) throws Exception {
        return body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(0), null, false))
                .andExpect(status().isAccepted()).andReturn()).get("id").asLong();
    }

    private void makeLookInterrupted(long bookingId) {
        jdbc.update("UPDATE saga_transactions SET status = 'IN_PROGRESS', updated_at = ? WHERE booking_id = ?",
                LocalDateTime.now().minusMinutes(10), bookingId);
    }

    @Test
    @DisplayName("Crash before payment: recovery rolls the booking back and frees the seat")
    void interruptedBeforePayment() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long bookingId = heldBooking(user, f);
        makeLookInterrupted(bookingId);

        assertThat(saga.recoverStuckSagas()).isEqualTo(1);

        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(seatStatus(f.seat(0))).isEqualTo("AVAILABLE");
        assertThat(sagas.findByBookingId(bookingId).orElseThrow().getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.recoverStuckSagas()).isZero(); // nothing left to recover
    }

    @Test
    @DisplayName("Crash after the payment was captured: recovery confirms the booking")
    void interruptedAfterPaymentCaptured() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long bookingId = heldBooking(user, f);
        jdbc.update("INSERT INTO payments (booking_id, amount, status, method, idempotency_key, request_hash, gateway_reference, created_at, updated_at) "
                        + "VALUES (?, 150.00, 'COMPLETED', 'TEST_SUCCESS', ?, 'hash', 'SIM-RECOVERY', NOW(6), NOW(6))",
                bookingId, newKey());
        makeLookInterrupted(bookingId);

        assertThat(saga.recoverStuckSagas()).isEqualTo(1);

        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(seatStatus(f.seat(0))).isEqualTo("BOOKED");
        assertThat(sagas.findByBookingId(bookingId).orElseThrow().getStatus()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    @DisplayName("Crash after capture AND the hold expired: recovery refunds and cancels")
    void interruptedAfterCaptureWithExpiredHold() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long bookingId = heldBooking(user, f);
        jdbc.update("INSERT INTO payments (booking_id, amount, status, method, idempotency_key, request_hash, gateway_reference, created_at, updated_at) "
                        + "VALUES (?, 150.00, 'COMPLETED', 'TEST_SUCCESS', ?, 'hash', 'SIM-RECOVERY', NOW(6), NOW(6))",
                bookingId, newKey());
        jdbc.update("UPDATE seats SET locked_until = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1), f.seat(0));
        makeLookInterrupted(bookingId);

        saga.recoverStuckSagas();

        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(payments.findByBookingIdOrderByIdDesc(bookingId).get(0).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(seatStatus(f.seat(0))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("Crash while the gateway call was in flight: the PENDING payment is failed and the booking rolled back")
    void interruptedDuringGatewayCall() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long bookingId = heldBooking(user, f);
        jdbc.update("INSERT INTO payments (booking_id, amount, status, method, idempotency_key, request_hash, created_at, updated_at) "
                        + "VALUES (?, 150.00, 'PENDING', 'TEST_SUCCESS', ?, 'hash', NOW(6), NOW(6))",
                bookingId, newKey());
        makeLookInterrupted(bookingId);

        saga.recoverStuckSagas();

        assertThat(payments.findByBookingIdOrderByIdDesc(bookingId).get(0).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(seatStatus(f.seat(0))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("A recently updated saga is left alone (it may still be running)")
    void freshSagaIsNotTouched() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long bookingId = heldBooking(user, f);
        jdbc.update("UPDATE saga_transactions SET status = 'IN_PROGRESS' WHERE booking_id = ?", bookingId);

        assertThat(saga.recoverStuckSagas()).isZero();
        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
    }
}
