package com.movieticket.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movieticket.payment.PaymentStatus;
import com.movieticket.saga.SagaStatus;
import com.movieticket.support.AbstractIntegrationTest;
import com.movieticket.user.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Timeout handling: expired holds are released, and an expired seat can never become BOOKED. */
class SeatLockExpiryIT extends AbstractIntegrationTest {

    private long holdSeat(User user, Fixture f, int seatIndex) throws Exception {
        return body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(seatIndex), null, false))
                .andExpect(status().isAccepted()).andReturn()).get("id").asLong();
    }

    private void expireLock(Long seatId) {
        jdbc.update("UPDATE seats SET locked_until = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1), seatId);
    }

    @Test
    @DisplayName("Seat lock expiry: scheduled release returns the seat to AVAILABLE and cancels the pending booking")
    void expiredLockIsReleased() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long bookingId = holdSeat(user, f, 0);
        assertThat(seatStatus(f.seat(0))).isEqualTo("TEMPORARILY_LOCKED");

        assertThat(saga.releaseExpiredLocks()).isZero(); // still valid: must not be touched
        assertThat(seatStatus(f.seat(0))).isEqualTo("TEMPORARILY_LOCKED");

        expireLock(f.seat(0));
        assertThat(saga.releaseExpiredLocks()).isEqualTo(1);

        assertThat(seatStatus(f.seat(0))).isEqualTo("AVAILABLE");
        Booking b = bookings.findById(bookingId).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getFailureReason()).startsWith("LOCK_EXPIRED");
        assertThat(sagas.findByBookingId(bookingId).orElseThrow().getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(outboxTypesFor(bookingId)).contains("SEAT_RELEASED", "BOOKING_CANCELLED");
        assertThat(notifications.findByUserIdOrderByIdDesc(user.getId())).hasSize(1);

        assertThat(saga.releaseExpiredLocks()).isZero(); // running the job again changes nothing
    }

    @Test
    @DisplayName("Paying after the hold expired (before the job ran): payment is refunded, seat is NOT booked")
    void payAfterExpiryIsRefunded() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long bookingId = holdSeat(user, f, 0);
        expireLock(f.seat(0)); // the expiry job has not run yet

        mvc.perform(paymentRequest(user, bookingId, "150.00", "TEST_SUCCESS", newKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"));

        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(bookings.findById(bookingId).orElseThrow().getFailureReason()).startsWith("SEAT_LOCK_EXPIRED");
        assertThat(seatStatus(f.seat(0))).isEqualTo("AVAILABLE"); // never BOOKED
        assertThat(payments.findByBookingIdOrderByIdDesc(bookingId).get(0).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("Paying after the expiry job already cancelled the booking is refused and charges nothing")
    void payAfterJobCancelled() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long bookingId = holdSeat(user, f, 0);
        expireLock(f.seat(0));
        saga.releaseExpiredLocks();

        mvc.perform(paymentRequest(user, bookingId, "150.00", "TEST_SUCCESS", newKey()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_NOT_PAYABLE"));
        assertThat(payments.count()).isZero();
        assertThat(seatStatus(f.seat(0))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("Another customer can take a seat whose hold has expired, even before the job runs")
    void expiredHoldCanBeTakenOver() throws Exception {
        User first = createUser("a@test.local");
        User second = createUser("b@test.local");
        Fixture f = createFixture();
        long firstBooking = holdSeat(first, f, 0);
        expireLock(f.seat(0));

        mvc.perform(bookingRequest(second, newKey(), f.showId(), f.seat(0), null, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(bookings.findById(firstBooking).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(seatStatus(f.seat(0))).isEqualTo("BOOKED");
        assertThat(count("SELECT COUNT(*) FROM bookings WHERE seat_id = ? AND status = 'CONFIRMED'", f.seat(0))).isEqualTo(1);
    }

    @Test
    @DisplayName("A held seat blocks other customers (409) until it is paid or released")
    void heldSeatBlocksOthers() throws Exception {
        User first = createUser("a@test.local");
        User second = createUser("b@test.local");
        Fixture f = createFixture();
        holdSeat(first, f, 0);

        mvc.perform(bookingRequest(second, newKey(), f.showId(), f.seat(0), null, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));
        assertThat(seatStatus(f.seat(0))).isEqualTo("TEMPORARILY_LOCKED");
    }
}
