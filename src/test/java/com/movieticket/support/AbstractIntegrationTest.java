package com.movieticket.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movieticket.booking.BookingRepository;
import com.movieticket.events.OutboxEventRepository;
import com.movieticket.movie.Movie;
import com.movieticket.movie.MovieRepository;
import com.movieticket.notification.NotificationRepository;
import com.movieticket.payment.PaymentRepository;
import com.movieticket.saga.BookingSagaOrchestrator;
import com.movieticket.saga.SagaRepository;
import com.movieticket.seat.Seat;
import com.movieticket.seat.SeatRepository;
import com.movieticket.security.AuthenticatedUser;
import com.movieticket.security.JwtService;
import com.movieticket.show.Show;
import com.movieticket.show.ShowRepository;
import com.movieticket.theatre.Theatre;
import com.movieticket.theatre.TheatreRepository;
import com.movieticket.user.Role;
import com.movieticket.user.User;
import com.movieticket.user.UserRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base class of all integration tests. They run the full application against a REAL MySQL database
 * (movie_ticket_booking_test_db by default) - there is no H2. The database is emptied before every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /** Random per test run, so no secret is stored anywhere. */
    protected static final String JWT_SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();
    protected static final String PASSWORD = "Password123";

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> JWT_SECRET);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository users;
    @Autowired protected MovieRepository movies;
    @Autowired protected TheatreRepository theatres;
    @Autowired protected ShowRepository shows;
    @Autowired protected SeatRepository seats;
    @Autowired protected BookingRepository bookings;
    @Autowired protected PaymentRepository payments;
    @Autowired protected NotificationRepository notifications;
    @Autowired protected SagaRepository sagas;
    @Autowired protected OutboxEventRepository outbox;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected JwtService jwtService;
    @Autowired protected BookingSagaOrchestrator saga;

    @BeforeEach
    protected void cleanDatabase() {
        // children first; seats.locked_by_booking_id is ON DELETE SET NULL so bookings can go before seats
        for (String table : List.of("outbox_events", "notifications", "saga_transactions", "payments", "bookings",
                "seats", "shows", "theatres", "movies", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** A movie, a theatre and one show (2 rows x 3 seats = A1..B3) priced at 150.00. */
    public record Fixture(Long movieId, Long theatreId, Long showId, List<Long> seatIds) {
        public Long seat(int index) {
            return seatIds.get(index);
        }
    }

    protected User createUser(String email) {
        return users.save(new User("Test " + email, email, encoder.encode(PASSWORD), Role.USER, LocalDateTime.now()));
    }

    protected User createAdmin() {
        return users.save(new User("Admin", "admin@test.local", encoder.encode(PASSWORD), Role.ADMIN, LocalDateTime.now()));
    }

    protected String tokenOf(User user) {
        return jwtService.generateToken(user);
    }

    protected AuthenticatedUser principal(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
    }

    protected Fixture createFixture() {
        Movie movie = movies.save(new Movie("Test Movie " + UUID.randomUUID(), "Drama", 120, "English", "desc"));
        Theatre theatre = theatres.save(new Theatre("Test Theatre", "Madurai", "1 Test Road", 3));
        Show show = shows.save(new Show(movie.getId(), theatre.getId(), 1, LocalDateTime.now().plusDays(2), new java.math.BigDecimal("150.00")));
        seats.saveAll(List.of("A1", "A2", "A3", "B1", "B2", "B3").stream().map(n -> new Seat(show.getId(), n)).toList());
        List<Long> ids = seats.findByShowIdOrderByIdAsc(show.getId()).stream().map(Seat::getId).toList();
        return new Fixture(movie.getId(), theatre.getId(), show.getId(), ids);
    }

    // ------------------------------------------------------------------ HTTP helpers

    protected MockHttpServletRequestBuilder bookingRequest(User user, String key, Long showId, Long seatId, String method, Boolean autoPay) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("showId", showId);
        body.put("seatId", seatId);
        if (method != null) {
            body.put("paymentMethod", method);
        }
        if (autoPay != null) {
            body.put("autoPay", autoPay);
        }
        return post("/api/bookings")
                .header("Authorization", "Bearer " + tokenOf(user))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder paymentRequest(User user, Long bookingId, String amount, String method, String key) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", bookingId);
        body.put("amount", amount);
        if (method != null) {
            body.put("paymentMethod", method);
        }
        body.put("idempotencyKey", key);
        return post("/api/payments")
                .header("Authorization", "Bearer " + tokenOf(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected String newKey() {
        return "key-" + UUID.randomUUID();
    }

    // ------------------------------------------------------------------ DB assertions helpers

    protected String seatStatus(Long seatId) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seatId);
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    protected List<String> outboxTypesFor(Long bookingId) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY id", String.class, bookingId);
    }
}
