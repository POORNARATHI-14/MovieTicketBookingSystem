package com.movieticket.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Durable progress record of one booking Saga, so an interrupted workflow can be recovered after a restart. */
@Entity
@Table(name = "saga_transactions")
public class SagaTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false, length = 30)
    private SagaStep currentStep;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SagaStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SagaTransaction() {
    }

    public SagaTransaction(Long bookingId, SagaStep step, SagaStatus status, LocalDateTime now) {
        this.bookingId = bookingId;
        this.currentStep = step;
        this.status = status;
        this.startedAt = now;
        this.updatedAt = now;
    }

    public void advance(SagaStep step, SagaStatus newStatus, String reason, LocalDateTime now) {
        this.currentStep = step;
        this.status = newStatus;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public SagaStep getCurrentStep() {
        return currentStep;
    }

    public SagaStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
