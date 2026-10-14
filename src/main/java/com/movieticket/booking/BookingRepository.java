package com.movieticket.booking;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    List<Booking> findByUserIdOrderByIdDesc(Long userId);

    /** SELECT ... FOR UPDATE: serialises payment initiation and cancellation for one booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    /** Compare-and-set status change. Returns 0 if the booking was not in {@code fromStatus}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE bookings
               SET status = :toStatus, failure_reason = :reason, updated_at = :now
             WHERE id = :id AND status = :fromStatus
            """, nativeQuery = true)
    int transition(@Param("id") Long id, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                   @Param("reason") String reason, @Param("now") LocalDateTime now);
}
