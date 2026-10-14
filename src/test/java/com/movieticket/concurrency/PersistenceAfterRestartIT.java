package com.movieticket.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movieticket.booking.Booking;
import com.movieticket.booking.BookingRepository;
import com.movieticket.booking.BookingStatus;
import com.movieticket.MovieTicketBookingApplication;
import com.movieticket.payment.Payment;
import com.movieticket.payment.PaymentRepository;
import com.movieticket.payment.PaymentStatus;
import com.movieticket.seat.SeatRepository;
import com.movieticket.seat.SeatStatus;
import com.movieticket.support.AbstractIntegrationTest;
import com.movieticket.user.User;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class PersistenceAfterRestartIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("Restart: a brand-new application instance still sees the persisted booking, payment and seat state in MySQL")
    void dataSurvivesRestart() throws Exception {
        User user = createUser("restart@test.local");
        Fixture f = createFixture();
        long bookingId = body(mvc.perform(bookingRequest(user, newKey(), f.showId(), f.seat(0), null, null))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        // start a SECOND, completely separate application context against the same database
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(MovieTicketBookingApplication.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                // command-line arguments have the highest precedence, so they beat the ${JWT_SECRET} placeholder
                .run("--app.jwt.secret=" + JWT_SECRET, "--spring.main.banner-mode=off")) {

            Booking booking = restarted.getBean(BookingRepository.class).findById(bookingId).orElseThrow();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(booking.getUserId()).isEqualTo(user.getId());

            List<Payment> paid = restarted.getBean(PaymentRepository.class).findByBookingIdOrderByIdDesc(bookingId);
            assertThat(paid).hasSize(1);
            assertThat(paid.get(0).getStatus()).isEqualTo(PaymentStatus.COMPLETED);

            assertThat(restarted.getBean(SeatRepository.class).findById(f.seat(0)).orElseThrow().getStatus())
                    .isEqualTo(SeatStatus.BOOKED);
        }
    }
}
