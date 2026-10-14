package com.movieticket.payment;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByBookingIdOrderByIdDesc(Long bookingId);

    List<Payment> findByBookingIdAndStatus(Long bookingId, PaymentStatus status);

    boolean existsByBookingIdAndStatusIn(Long bookingId, Collection<PaymentStatus> statuses);

    /** Compare-and-set status change: returns 0 if the payment was not in the expected state. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE payments
               SET status = :toStatus, gateway_reference = COALESCE(:reference, gateway_reference),
                   failure_reason = :reason, updated_at = :now
             WHERE id = :id AND status = :fromStatus
            """, nativeQuery = true)
    int transition(@Param("id") Long id, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                   @Param("reference") String reference, @Param("reason") String reason, @Param("now") LocalDateTime now);
}
