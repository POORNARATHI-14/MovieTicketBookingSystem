package com.movieticket.booking;

import com.movieticket.booking.BookingDtos.BookingResponse;
import com.movieticket.common.exception.ApiException;
import com.movieticket.saga.SagaRepository;
import com.movieticket.saga.SagaTransaction;
import com.movieticket.seat.Seat;
import com.movieticket.seat.SeatRepository;
import com.movieticket.seat.SeatStatus;
import com.movieticket.security.AuthenticatedUser;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of bookings: retrieval, history, and mapping to API responses. The write side is the Saga. */
@Service
public class BookingService {

    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final SagaRepository sagas;

    public BookingService(BookingRepository bookings, SeatRepository seats, SagaRepository sagas) {
        this.bookings = bookings;
        this.seats = seats;
        this.sagas = sagas;
    }

    @Transactional(readOnly = true)
    public BookingResponse get(Long id, AuthenticatedUser caller) {
        Booking b = find(id);
        requireOwnerOrAdmin(b, caller);
        return toResponse(b);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listForUser(Long userId, AuthenticatedUser caller) {
        if (!caller.isAdmin() && !caller.id().equals(userId)) {
            throw ApiException.forbidden("ACCESS_DENIED", "You can only view your own bookings");
        }
        return bookings.findByUserIdOrderByIdDesc(userId).stream().map(this::toResponse).toList();
    }

    /** Admin overview: the most recent bookings of all users (capped). Access is restricted in SecurityConfig. */
    @Transactional(readOnly = true)
    public List<BookingResponse> listAll(int limit) {
        return bookings.findAll(org.springframework.data.domain.PageRequest.of(0, limit,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")))
                .getContent().stream().map(this::toResponse).toList();
    }

    /** Used by the Saga after its own transactions have committed (no authorization). */
    @Transactional(readOnly = true)
    public BookingResponse responseFor(Long bookingId) {
        return toResponse(find(bookingId));
    }

    public void requireOwnerOrAdmin(Booking booking, AuthenticatedUser caller) {
        if (!caller.isAdmin() && !caller.id().equals(booking.getUserId())) {
            throw ApiException.forbidden("ACCESS_DENIED", "This booking belongs to another user");
        }
    }

    private Booking find(Long id) {
        return bookings.findById(id)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking " + id + " does not exist"));
    }

    private BookingResponse toResponse(Booking b) {
        Seat seat = seats.findById(b.getSeatId()).orElse(null);
        SagaTransaction saga = sagas.findByBookingId(b.getId()).orElse(null);
        LocalDateTime lockExpiresAt = null;
        if (seat != null && b.getStatus() == BookingStatus.PENDING && seat.getStatus() == SeatStatus.TEMPORARILY_LOCKED
                && b.getId().equals(seat.getLockedByBookingId())) {
            lockExpiresAt = seat.getLockedUntil();
        }
        return new BookingResponse(b.getId(), b.getUserId(), b.getShowId(), b.getSeatId(),
                seat != null ? seat.getSeatNumber() : null, b.getStatus(), b.getTotalAmount(), b.getIdempotencyKey(),
                b.getFailureReason(), saga != null ? saga.getCurrentStep() : null,
                saga != null ? saga.getStatus() : null, lockExpiresAt, b.getCreatedAt(), b.getUpdatedAt());
    }
}
