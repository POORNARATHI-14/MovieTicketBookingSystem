package com.movieticket.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.movieticket.booking.BookingDtos.BookingResult;
import com.movieticket.booking.BookingDtos.CreateBookingRequest;
import com.movieticket.booking.BookingStatus;
import com.movieticket.common.exception.ApiException;
import com.movieticket.payment.PaymentDtos.PaymentRequest;
import com.movieticket.payment.PaymentDtos.PaymentResult;
import com.movieticket.payment.PaymentMethod;
import com.movieticket.support.AbstractIntegrationTest;
import com.movieticket.user.User;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Real parallel requests (separate threads, separate database transactions) against real MySQL.
 * The final database state is verified, not just the return values.
 */
class ConcurrentBookingIT extends AbstractIntegrationTest {

    private static final int THREADS = 12;

    /** Starts all callables at the same instant and returns their outcomes (value or exception). */
    private <T> List<Object> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : futures) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    @Test
    @DisplayName("12 users request the SAME seat at the same instant: exactly one booking succeeds, the rest get 409")
    void twelveUsersOneSeat() throws Exception {
        Fixture f = createFixture();
        List<User> people = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            people.add(createUser("racer" + i + "@test.local"));
        }
        List<Callable<BookingResult>> tasks = new ArrayList<>();
        for (User u : people) {
            tasks.add(() -> saga.execute(principal(u), new CreateBookingRequest(f.showId(), f.seat(0), PaymentMethod.TEST_SUCCESS, true), newKey()));
        }

        List<Object> outcomes = runTogether(tasks);

        long winners = outcomes.stream().filter(o -> o instanceof BookingResult r && r.booking().status() == BookingStatus.CONFIRMED).count();
        List<Object> losers = outcomes.stream().filter(o -> !(o instanceof BookingResult)).toList();
        assertThat(winners).as("exactly one winner").isEqualTo(1);
        assertThat(losers).hasSize(THREADS - 1);
        for (Object loser : losers) {
            assertThat(loser).as("losers must get a clean business error, not a crash").isInstanceOf(ApiException.class);
            assertThat(((ApiException) loser).getStatus().value()).isEqualTo(409);
            assertThat(((ApiException) loser).getCode()).isEqualTo("SEAT_UNAVAILABLE");
        }

        // final database state
        assertThat(seatStatus(f.seat(0))).isEqualTo("BOOKED");
        assertThat(count("SELECT COUNT(*) FROM bookings WHERE seat_id = ? AND status = 'CONFIRMED'", f.seat(0))).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM bookings WHERE status = 'PENDING'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM bookings WHERE status = 'CANCELLED'")).isEqualTo(THREADS - 1);
        assertThat(count("SELECT COUNT(*) FROM payments WHERE status = 'COMPLETED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM payments")).isEqualTo(1); // losers were never charged
        assertThat(count("SELECT COUNT(*) FROM saga_transactions WHERE status = 'COMPLETED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM saga_transactions WHERE status = 'COMPENSATED'")).isEqualTo(THREADS - 1);
    }

    @Test
    @DisplayName("12 users each take a DIFFERENT seat in parallel: all succeed (locking is per seat, not global)")
    void differentSeatsInParallel() throws Exception {
        Fixture f = createFixture(); // 6 seats
        Fixture g = createFixture(); // another show, 6 more seats
        List<Long> showIds = new ArrayList<>();
        List<Long> seatIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            showIds.add(f.showId());
            seatIds.add(f.seat(i));
            showIds.add(g.showId());
            seatIds.add(g.seat(i));
        }
        List<Callable<BookingResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            User u = createUser("p" + i + "@test.local");
            Long showId = showIds.get(i);
            Long seatId = seatIds.get(i);
            tasks.add(() -> saga.execute(principal(u), new CreateBookingRequest(showId, seatId, null, null), newKey()));
        }

        List<Object> outcomes = runTogether(tasks);

        for (Object o : outcomes) {
            assertThat(o).isInstanceOf(BookingResult.class);
            assertThat(((BookingResult) o).booking().status()).isEqualTo(BookingStatus.CONFIRMED);
        }
        assertThat(count("SELECT COUNT(*) FROM seats WHERE status = 'BOOKED'")).isEqualTo(THREADS);
    }

    @Test
    @DisplayName("Simultaneous duplicate booking requests with the same Idempotency-Key create ONE booking and ONE payment")
    void simultaneousDuplicateBookingRequests() throws Exception {
        Fixture f = createFixture();
        User user = createUser("dup@test.local");
        String key = newKey();
        List<Callable<BookingResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> saga.execute(principal(user), new CreateBookingRequest(f.showId(), f.seat(0), PaymentMethod.TEST_SUCCESS, true), key));
        }

        List<Object> outcomes = runTogether(tasks);

        Set<Long> ids = outcomes.stream().map(o -> {
            assertThat(o).as("every duplicate gets the stored result, not an error").isInstanceOf(BookingResult.class);
            return ((BookingResult) o).booking().id();
        }).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !((BookingResult) o).replayed()).count()).isEqualTo(1);

        assertThat(bookings.count()).isEqualTo(1);
        assertThat(payments.count()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'BOOKING_CONFIRMED'")).isEqualTo(1);
        assertThat(seatStatus(f.seat(0))).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("Simultaneous duplicate payment requests with the same key charge ONCE")
    void simultaneousDuplicatePayments() throws Exception {
        Fixture f = createFixture();
        User user = createUser("dup@test.local");
        long bookingId = saga.execute(principal(user), new CreateBookingRequest(f.showId(), f.seat(0), null, false), newKey())
                .booking().id();
        String payKey = newKey();
        List<Callable<PaymentResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> saga.payForBooking(principal(user),
                    new PaymentRequest(bookingId, new BigDecimal("150.00"), PaymentMethod.TEST_SUCCESS, payKey)));
        }

        List<Object> outcomes = runTogether(tasks);

        Set<Long> paymentIds = outcomes.stream().map(o -> {
            assertThat(o).as("duplicates get the original result").isInstanceOf(PaymentResult.class);
            return ((PaymentResult) o).payment().id();
        }).collect(Collectors.toSet());
        assertThat(paymentIds).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !((PaymentResult) o).replayed()).count()).isEqualTo(1);
        assertThat(payments.count()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM payments WHERE status = 'COMPLETED'")).isEqualTo(1);
        assertThat(seatStatus(f.seat(0))).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("Concurrent payments with DIFFERENT keys for one booking: only one is charged")
    void concurrentDifferentKeyPayments() throws Exception {
        Fixture f = createFixture();
        User user = createUser("dup@test.local");
        long bookingId = saga.execute(principal(user), new CreateBookingRequest(f.showId(), f.seat(0), null, false), newKey())
                .booking().id();
        List<Callable<PaymentResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> saga.payForBooking(principal(user),
                    new PaymentRequest(bookingId, new BigDecimal("150.00"), PaymentMethod.TEST_SUCCESS, newKey())));
        }

        List<Object> outcomes = runTogether(tasks);

        assertThat(outcomes.stream().filter(o -> o instanceof PaymentResult).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o instanceof ApiException).count()).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM payments")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM payments WHERE status = 'COMPLETED'")).isEqualTo(1);
    }
}
