package com.movieticket.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.movieticket.events.OutboxStatus;
import com.movieticket.notification.Notification;
import com.movieticket.notification.NotificationType;
import com.movieticket.payment.Payment;
import com.movieticket.payment.PaymentStatus;
import com.movieticket.saga.SagaStatus;
import com.movieticket.saga.SagaTransaction;
import com.movieticket.seat.Seat;
import com.movieticket.seat.SeatStatus;
import com.movieticket.support.AbstractIntegrationTest;
import com.movieticket.user.User;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The booking Saga end to end: success path, every compensation path, and request validation. */
class BookingSagaIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("Book an available seat: booking CONFIRMED, seat BOOKED, payment COMPLETED, notification + events stored")
    void bookAvailableSeat() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();

        JsonNode res = body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(0), "TEST_SUCCESS", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.seatNumber").value("A1"))
                .andExpect(jsonPath("$.totalAmount").value(150.00))
                .andExpect(jsonPath("$.sagaStatus").value("COMPLETED"))
                .andReturn());
        long bookingId = res.get("id").asLong();

        assertThat(seatStatus(f.seat(0))).isEqualTo("BOOKED");
        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        List<Payment> paid = payments.findByBookingIdOrderByIdDesc(bookingId);
        assertThat(paid).hasSize(1);
        assertThat(paid.get(0).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(paid.get(0).getAmount()).isEqualByComparingTo("150.00");
        assertThat(paid.get(0).getGatewayReference()).startsWith("SIM-");

        List<Notification> notes = notifications.findByUserIdOrderByIdDesc(user.getId());
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).getType()).isEqualTo(NotificationType.BOOKING_CONFIRMED);

        SagaTransaction s = sagas.findByBookingId(bookingId).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SagaStatus.COMPLETED);

        // events were written to the outbox in the same transactions (RabbitMQ is disabled, so they stay PENDING)
        assertThat(outboxTypesFor(bookingId)).containsExactly("BOOKING_CREATED", "SEAT_LOCKED", "PAYMENT_COMPLETED", "BOOKING_CONFIRMED");
        assertThat(outbox.countByStatus(OutboxStatus.PENDING)).isEqualTo(4);
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE event_type='BOOKING_CONFIRMED'", String.class);
        assertThat(payload).contains("BookingConfirmed").contains("eventId");

        // read endpoints
        mvc.perform(get("/api/bookings/" + bookingId).header("Authorization", "Bearer " + tokenOf(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(get("/api/bookings").param("userId", String.valueOf(user.getId()))
                        .header("Authorization", "Bearer " + tokenOf(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("Payment failure: booking CANCELLED, seat released, payment kept as FAILED, user notified, then seat is bookable again")
    void paymentFailureCompensates() throws Exception {
        User user = createUser("a@test.local");
        User other = createUser("b@test.local");
        Fixture f = createFixture();

        JsonNode res = body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(1), "TEST_DECLINED", null))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.sagaStatus").value("COMPENSATED"))
                .andReturn());
        long bookingId = res.get("id").asLong();
        assertThat(res.get("failureReason").asText()).startsWith("PAYMENT_FAILED");

        Seat seat = seats.findById(f.seat(1)).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getLockedByBookingId()).isNull();
        assertThat(seat.getLockedUntil()).isNull();

        List<Payment> attempts = payments.findByBookingIdOrderByIdDesc(bookingId);
        assertThat(attempts).hasSize(1); // audit record is kept
        assertThat(attempts.get(0).getStatus()).isEqualTo(PaymentStatus.FAILED);

        assertThat(notifications.findByUserIdOrderByIdDesc(user.getId()))
                .extracting(Notification::getType).containsExactly(NotificationType.BOOKING_FAILED);
        assertThat(outboxTypesFor(bookingId)).contains("PAYMENT_FAILED", "SEAT_RELEASED", "BOOKING_CANCELLED");

        // the compensation really freed the seat for somebody else
        mvc.perform(bookingRequest(other, newKey(), f.showId(), f.seat(1), "TEST_SUCCESS", null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("Gateway timeout is handled as a failed payment (402) and the seat is released")
    void paymentTimeout() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        JsonNode res = body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(0), "TEST_TIMEOUT", null))
                .andExpect(status().isPaymentRequired()).andReturn());
        assertThat(res.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(res.get("failureReason").asText()).startsWith("PAYMENT_TIMEOUT");
        assertThat(seatStatus(f.seat(0))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("Requesting an already booked seat is rejected with 409 and does not disturb the first booking")
    void alreadyBookedSeat() throws Exception {
        User first = createUser("a@test.local");
        User second = createUser("b@test.local");
        Fixture f = createFixture();

        long firstId = body(mvc.perform(bookingRequest(first, newKey(), f.showId(), f.seat(2), null, null))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        mvc.perform(bookingRequest(second, newKey(), f.showId(), f.seat(2), null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));

        assertThat(bookings.findById(firstId).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(seats.findById(f.seat(2)).orElseThrow().getLockedByBookingId()).isEqualTo(firstId);
        assertThat(count("SELECT COUNT(*) FROM bookings WHERE seat_id = ? AND status = 'CONFIRMED'", f.seat(2))).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM payments WHERE status = 'COMPLETED'")).isEqualTo(1); // second user was never charged
        assertThat(notifications.findByUserIdOrderByIdDesc(second.getId()))
                .extracting(Notification::getType).containsExactly(NotificationType.BOOKING_FAILED);
    }

    @Test
    @DisplayName("Payment captured but a later step fails: payment is REFUNDED, booking CANCELLED, seat released")
    void refundCompensation() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();

        JsonNode res = body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(0), "TEST_CAPTURE_THEN_FAIL", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andReturn());
        long bookingId = res.get("id").asLong();

        assertThat(res.get("failureReason").asText()).startsWith("POST_PAYMENT_FAILURE");
        Payment p = payments.findByBookingIdOrderByIdDesc(bookingId).get(0);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.getFailureReason()).contains("Refunded");
        assertThat(seatStatus(f.seat(0))).isEqualTo("AVAILABLE");
        assertThat(outboxTypesFor(bookingId)).contains("PAYMENT_COMPLETED", "PAYMENT_REFUNDED", "SEAT_RELEASED", "BOOKING_CANCELLED");
        assertThat(notifications.findByUserIdOrderByIdDesc(user.getId()).get(0).getMessage()).contains("refunded");
    }

    @Test
    @DisplayName("Cancelling a confirmed booking refunds the payment and frees the seat")
    void cancelConfirmedBooking() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long bookingId = body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(0), null, null))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", "Bearer " + tokenOf(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(seatStatus(f.seat(0))).isEqualTo("AVAILABLE");
        assertThat(payments.findByBookingIdOrderByIdDesc(bookingId).get(0).getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        // cancelling again is harmless
        mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", "Bearer " + tokenOf(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("Invalid ids: unknown show 404, unknown seat 404, seat of another show 400")
    void invalidIds() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        Fixture other = createFixture();

        mvc.perform(bookingRequest(user, newKey(), 99999L, f.seat(0), null, null))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOW_NOT_FOUND"));
        mvc.perform(bookingRequest(user, newKey(), f.showId(), 99999L, null, null))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
        mvc.perform(bookingRequest(user, newKey(), f.showId(), other.seat(0), null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SEAT_NOT_IN_SHOW"));
        mvc.perform(get("/api/bookings/99999").header("Authorization", "Bearer " + tokenOf(user)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));

        assertThat(bookings.count()).isZero(); // nothing was created for invalid requests
        assertThat(seatStatus(f.seat(0))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("Missing/invalid Idempotency-Key, bad body and missing token are rejected")
    void requestValidation() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();

        mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + tokenOf(user))
                        .contentType("application/json").content("{\"showId\":" + f.showId() + ",\"seatId\":" + f.seat(0) + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        mvc.perform(bookingRequest(user, "short", f.showId(), f.seat(0), null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + tokenOf(user))
                        .header("Idempotency-Key", newKey()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(0), "NOT_A_METHOD", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        mvc.perform(post("/api/bookings").header("Idempotency-Key", newKey()).contentType("application/json")
                        .content("{\"showId\":1,\"seatId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Users can only see their own bookings, payments and notifications")
    void ownership() throws Exception {
        User owner = createUser("a@test.local");
        User stranger = createUser("b@test.local");
        Fixture f = createFixture();
        long bookingId = body(mvc.perform(bookingRequest(owner, newKey(), f.showId(), f.seat(0), null, null))
                .andReturn()).get("id").asLong();
        long paymentId = payments.findByBookingIdOrderByIdDesc(bookingId).get(0).getId();
        String strangerToken = "Bearer " + tokenOf(stranger);

        mvc.perform(get("/api/bookings/" + bookingId).header("Authorization", strangerToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/payments/" + paymentId).header("Authorization", strangerToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/bookings").param("userId", String.valueOf(owner.getId())).header("Authorization", strangerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/notifications/" + owner.getId()).header("Authorization", strangerToken)).andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", strangerToken)).andExpect(status().isForbidden());

        String ownerToken = "Bearer " + tokenOf(owner);
        mvc.perform(get("/api/payments/" + paymentId).header("Authorization", ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/notifications/" + owner.getId()).header("Authorization", ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("BOOKING_CONFIRMED"));
    }
}
