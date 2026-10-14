package com.movieticket.saga;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaRepository extends JpaRepository<SagaTransaction, Long> {

    Optional<SagaTransaction> findByBookingId(Long bookingId);

    List<SagaTransaction> findByStatusAndUpdatedAtBefore(SagaStatus status, LocalDateTime cutoff);
}
