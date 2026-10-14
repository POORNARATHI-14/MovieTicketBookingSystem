package com.movieticket.seat;

import com.movieticket.common.exception.ApiException;
import com.movieticket.seat.SeatDtos.SeatResponse;
import com.movieticket.show.ShowRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seat inventory plus the atomic state transitions used by the booking Saga. The transition methods
 * require an ongoing transaction ({@code MANDATORY}) so that a seat change and the Saga/outbox records
 * describing it always commit or roll back together.
 */
@Service
public class SeatService {

    private final SeatRepository seats;
    private final ShowRepository shows;
    private final Clock clock;

    public SeatService(SeatRepository seats, ShowRepository shows, Clock clock) {
        this.seats = seats;
        this.shows = shows;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ inventory

    @Transactional(readOnly = true)
    public List<SeatResponse> listForShow(Long showId) {
        requireShow(showId);
        return seats.findByShowIdOrderByIdAsc(showId).stream().map(SeatResponse::from).toList();
    }

    /** Generates seats A1..A{perRow}, B1.. etc. Joins the caller's transaction. */
    @Transactional
    public void createSeatsForShow(Long showId, int rows, int seatsPerRow) {
        List<Seat> batch = new ArrayList<>(rows * seatsPerRow);
        for (int r = 0; r < rows; r++) {
            char rowLetter = (char) ('A' + r);
            for (int n = 1; n <= seatsPerRow; n++) {
                batch.add(new Seat(showId, rowLetter + String.valueOf(n)));
            }
        }
        seats.saveAll(batch);
    }

    @Transactional
    public List<SeatResponse> addSeats(Long showId, List<String> seatNumbers) {
        requireShow(showId);
        Set<String> requested = new LinkedHashSet<>();
        for (String n : seatNumbers) {
            requested.add(n.trim().toUpperCase());
        }
        Set<String> existing = seats.findSeatNumbersByShowId(showId);
        for (String n : requested) {
            if (existing.contains(n)) {
                throw ApiException.conflict("SEAT_ALREADY_EXISTS", "Seat " + n + " already exists for show " + showId);
            }
        }
        List<Seat> created = seats.saveAll(requested.stream().map(n -> new Seat(showId, n)).toList());
        return created.stream().map(SeatResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public long countAll(Long showId) {
        return seats.countByShowId(showId);
    }

    @Transactional(readOnly = true)
    public long countAvailable(Long showId) {
        return seats.countByShowIdAndStatus(showId, SeatStatus.AVAILABLE);
    }

    @Transactional(readOnly = true)
    public Seat requireSeatOfShow(Long seatId, Long showId) {
        return seats.findByIdAndShowId(seatId, showId).orElseThrow(() -> {
            if (seats.existsById(seatId)) {
                return ApiException.badRequest("SEAT_NOT_IN_SHOW",
                        "Seat " + seatId + " does not belong to show " + showId);
            }
            return ApiException.notFound("SEAT_NOT_FOUND", "Seat " + seatId + " does not exist");
        });
    }

    @Transactional(readOnly = true)
    public List<Long> findExpiredLockSeatIds(LocalDateTime now) {
        return seats.findTop100ByStatusAndLockedUntilBeforeOrderByLockedUntilAsc(SeatStatus.TEMPORARILY_LOCKED, now)
                .stream().map(Seat::getId).toList();
    }

    // ------------------------------------------------------------------ atomic transitions

    /** @return true if THIS call obtained the lock; false if the seat was not AVAILABLE. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lock(Long seatId, Long bookingId, LocalDateTime until) {
        return seats.lockIfAvailable(seatId, bookingId, until) == 1;
    }

    /** @return true only if the lock is still valid and owned by the booking; false e.g. after expiry. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean confirm(Long seatId, Long bookingId) {
        return seats.confirmIfLockedBy(seatId, bookingId, LocalDateTime.now(clock)) == 1;
    }

    /** Compensation: frees a lock held by this booking. Returns false if the booking no longer holds it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean release(Long seatId, Long bookingId) {
        return seats.releaseIfLockedBy(seatId, bookingId) == 1;
    }

    /** Frees a lock only if it has really expired. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseExpired(Long seatId, Long bookingId) {
        return seats.releaseIfExpired(seatId, bookingId, LocalDateTime.now(clock)) == 1;
    }

    /** Frees a BOOKED seat when its confirmed booking is cancelled. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseBooked(Long seatId, Long bookingId) {
        return seats.releaseBookedBy(seatId, bookingId) == 1;
    }

    private void requireShow(Long showId) {
        if (!shows.existsById(showId)) {
            throw ApiException.notFound("SHOW_NOT_FOUND", "Show " + showId + " does not exist");
        }
    }
}
