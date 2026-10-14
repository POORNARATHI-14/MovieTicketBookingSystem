package com.movieticket.booking;

import com.movieticket.booking.BookingDtos.BookingResponse;
import com.movieticket.booking.BookingDtos.BookingResult;
import com.movieticket.booking.BookingDtos.CreateBookingRequest;
import com.movieticket.common.exception.ApiException;
import com.movieticket.saga.BookingSagaOrchestrator;
import com.movieticket.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final BookingSagaOrchestrator saga;
    private final BookingService service;

    public BookingController(BookingSagaOrchestrator saga, BookingService service) {
        this.saga = saga;
        this.service = service;
    }

    /**
     * Executes the booking Saga. Requires the header {@code Idempotency-Key}; retrying with the same key
     * and the same body returns the original result instead of creating another booking.
     */
    @PostMapping
    public ResponseEntity<BookingResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @Valid @RequestBody CreateBookingRequest request,
                                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        if (!KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 8-64 characters: letters, digits, '-' or '_'");
        }
        BookingResult result = saga.execute(caller, request, idempotencyKey);
        return ResponseEntity.status(statusFor(result)).body(result.booking());
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.get(id, caller);
    }

    /** Booking history. Without userId the caller's own bookings are returned. */
    @GetMapping
    public List<BookingResponse> list(@RequestParam(required = false) Long userId,
                                      @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.listForUser(userId != null ? userId : caller.id(), caller);
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return saga.cancelBooking(caller, id);
    }

    private HttpStatus statusFor(BookingResult result) {
        if (result.replayed()) {
            return HttpStatus.OK;
        }
        BookingResponse b = result.booking();
        return switch (b.status()) {
            case CONFIRMED -> HttpStatus.CREATED;
            case PENDING -> HttpStatus.ACCEPTED;
            case CANCELLED -> b.failureReason() != null && b.failureReason().startsWith("PAYMENT")
                    ? HttpStatus.PAYMENT_REQUIRED
                    : HttpStatus.CONFLICT;
        };
    }
}
