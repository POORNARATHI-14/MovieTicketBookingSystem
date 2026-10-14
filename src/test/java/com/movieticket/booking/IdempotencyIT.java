package com.movieticket.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.movieticket.support.AbstractIntegrationTest;
import com.movieticket.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Retries must never create a second booking or a second charge. */
class IdempotencyIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("Retrying a successful booking with the same key returns the original result and creates nothing new")
    void bookingRetryReturnsOriginal() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        String key = newKey();

        JsonNode first = body(mvc.perform(bookingRequest(user, key, f.showId(), f.seat(0), null, null))
                .andExpect(status().isCreated()).andReturn());
        JsonNode retry = body(mvc.perform(bookingRequest(user, key, f.showId(), f.seat(0), null, null))
                .andExpect(status().isOk()) // 200 = replay of the stored result
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn());

        assertThat(retry.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(bookings.count()).isEqualTo(1);
        assertThat(payments.count()).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_COMPLETED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("Retrying a failed booking with the same key does not charge again")
    void failedBookingRetryDoesNotRecharge() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        String key = newKey();

        mvc.perform(bookingRequest(user, key, f.showId(), f.seat(0), "TEST_DECLINED", null)).andExpect(status().isPaymentRequired());
        mvc.perform(bookingRequest(user, key, f.showId(), f.seat(0), "TEST_DECLINED", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(bookings.count()).isEqualTo(1);
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Reusing a key for a different request is rejected with 422")
    void keyReuseWithDifferentRequest() throws Exception {
        User user = createUser("a@test.local");
        User other = createUser("b@test.local");
        Fixture f = createFixture();
        String key = newKey();

        mvc.perform(bookingRequest(user, key, f.showId(), f.seat(0), null, null)).andExpect(status().isCreated());
        mvc.perform(bookingRequest(user, key, f.showId(), f.seat(1), null, null))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(bookingRequest(other, key, f.showId(), f.seat(0), null, null)) // another user cannot hijack the key
                .andExpect(status().isUnprocessableEntity());
        assertThat(bookings.count()).isEqualTo(1);
        assertThat(seatStatus(f.seat(1))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("Retrying a payment with the same key creates no duplicate payment record or charge")
    void paymentRetry() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long bookingId = body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(0), null, false))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sagaStatus").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.lockExpiresAt").exists())
                .andReturn()).get("id").asLong();
        assertThat(seatStatus(f.seat(0))).isEqualTo("TEMPORARILY_LOCKED");

        String payKey = newKey();
        JsonNode first = body(mvc.perform(paymentRequest(user, bookingId, "150.00", "TEST_SUCCESS", payKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
                .andReturn());
        JsonNode retry = body(mvc.perform(paymentRequest(user, bookingId, "150.00", "TEST_SUCCESS", payKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn());

        assertThat(retry.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(payments.count()).isEqualTo(1);
        assertThat(seatStatus(f.seat(0))).isEqualTo("BOOKED");
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_COMPLETED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("Payment validation: wrong amount (400), key reused for another booking (422), booking already paid (409)")
    void paymentValidation() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long b1 = body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(0), null, false)).andReturn()).get("id").asLong();
        long b2 = body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(1), null, false)).andReturn()).get("id").asLong();

        mvc.perform(paymentRequest(user, b1, "99.00", "TEST_SUCCESS", newKey()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        mvc.perform(paymentRequest(user, b1, "-5", "TEST_SUCCESS", newKey()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(paymentRequest(user, 99999L, "150.00", "TEST_SUCCESS", newKey()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
        assertThat(payments.count()).isZero();

        String key = newKey();
        mvc.perform(paymentRequest(user, b1, "150.00", "TEST_SUCCESS", key)).andExpect(status().isCreated());
        mvc.perform(paymentRequest(user, b2, "150.00", "TEST_SUCCESS", key))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(paymentRequest(user, b1, "150.00", "TEST_SUCCESS", newKey()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_NOT_PAYABLE"));
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("A failed payment cancels the booking (compensation), so that booking cannot be paid again")
    void retryAfterFailedPayment() throws Exception {
        User user = createUser("a@test.local");
        Fixture f = createFixture();
        long bookingId = body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(0), null, false)).andReturn()).get("id").asLong();

        mvc.perform(paymentRequest(user, bookingId, "150.00", "TEST_DECLINED", newKey())).andExpect(status().isPaymentRequired());
        // FAILED payment cancelled the booking (compensation), so a second attempt is refused
        mvc.perform(paymentRequest(user, bookingId, "150.00", "TEST_SUCCESS", newKey()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_NOT_PAYABLE"));
        assertThat(payments.count()).isEqualTo(1);
        assertThat(seatStatus(f.seat(0))).isEqualTo("AVAILABLE");
    }
}
