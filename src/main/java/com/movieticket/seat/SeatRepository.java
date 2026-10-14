package com.movieticket.seat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByShowIdOrderByIdAsc(Long showId);

    Optional<Seat> findByIdAndShowId(Long id, Long showId);

    long countByShowId(Long showId);

    long countByShowIdAndStatus(Long showId, SeatStatus status);

    @Query("select s.seatNumber from Seat s where s.showId = :showId")
    Set<String> findSeatNumbersByShowId(@Param("showId") Long showId);

    List<Seat> findTop100ByStatusAndLockedUntilBeforeOrderByLockedUntilAsc(SeatStatus status, LocalDateTime now);

    // ------------------------------------------------------------------------------------------
    // Atomic state transitions. Each statement checks the CURRENT state inside the UPDATE itself,
    // so MySQL's row lock makes concurrent callers take turns: the first changes the row (1 row
    // affected), the others find the condition false (0 rows affected). The return value is the
    // answer to "did I win?".
    // ------------------------------------------------------------------------------------------

    /** AVAILABLE -> TEMPORARILY_LOCKED for the given booking. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats
               SET status = 'TEMPORARILY_LOCKED', locked_until = :until, locked_by_booking_id = :bookingId
             WHERE id = :seatId AND status = 'AVAILABLE'
            """, nativeQuery = true)
    int lockIfAvailable(@Param("seatId") Long seatId, @Param("bookingId") Long bookingId,
                        @Param("until") LocalDateTime until);

    /** TEMPORARILY_LOCKED -> BOOKED, only by the booking that holds the lock and only while the lock is still valid. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats
               SET status = 'BOOKED', locked_until = NULL
             WHERE id = :seatId AND status = 'TEMPORARILY_LOCKED'
               AND locked_by_booking_id = :bookingId AND locked_until > :now
            """, nativeQuery = true)
    int confirmIfLockedBy(@Param("seatId") Long seatId, @Param("bookingId") Long bookingId,
                          @Param("now") LocalDateTime now);

    /** TEMPORARILY_LOCKED -> AVAILABLE (compensation), only for the booking that owns the lock. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats
               SET status = 'AVAILABLE', locked_until = NULL, locked_by_booking_id = NULL
             WHERE id = :seatId AND status = 'TEMPORARILY_LOCKED' AND locked_by_booking_id = :bookingId
            """, nativeQuery = true)
    int releaseIfLockedBy(@Param("seatId") Long seatId, @Param("bookingId") Long bookingId);

    /** TEMPORARILY_LOCKED -> AVAILABLE because the lock has expired. Refuses to touch a still-valid lock. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats
               SET status = 'AVAILABLE', locked_until = NULL, locked_by_booking_id = NULL
             WHERE id = :seatId AND status = 'TEMPORARILY_LOCKED'
               AND locked_by_booking_id <=> :bookingId AND locked_until <= :now
            """, nativeQuery = true)
    int releaseIfExpired(@Param("seatId") Long seatId, @Param("bookingId") Long bookingId,
                         @Param("now") LocalDateTime now);

    /** BOOKED -> AVAILABLE when a confirmed booking is cancelled. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats
               SET status = 'AVAILABLE', locked_until = NULL, locked_by_booking_id = NULL
             WHERE id = :seatId AND status = 'BOOKED' AND locked_by_booking_id = :bookingId
            """, nativeQuery = true)
    int releaseBookedBy(@Param("seatId") Long seatId, @Param("bookingId") Long bookingId);
}
