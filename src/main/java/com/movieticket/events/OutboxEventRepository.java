package com.movieticket.events;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims the oldest unpublished events. SKIP LOCKED lets several application instances run the
     * relay at the same time without publishing the same row twice. Must run inside a transaction.
     */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> lockPending(@Param("limit") int limit);

    List<OutboxEvent> findByAggregateIdOrderByIdAsc(Long aggregateId);

    long countByStatus(OutboxStatus status);
}
