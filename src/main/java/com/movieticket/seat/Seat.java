package com.movieticket.seat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A seat of one show. Its status is NEVER changed by "load, modify, save" in Java: all transitions are
 * atomic conditional UPDATE statements in {@link SeatRepository}, so concurrent requests cannot both win.
 */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "seat_number", nullable = false, length = 10)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "locked_by_booking_id")
    private Long lockedByBookingId;

    protected Seat() {
    }

    public Seat(Long showId, String seatNumber) {
        this.showId = showId;
        this.seatNumber = seatNumber;
    }

    public Long getId() {
        return id;
    }

    public Long getShowId() {
        return showId;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public Long getLockedByBookingId() {
        return lockedByBookingId;
    }
}
