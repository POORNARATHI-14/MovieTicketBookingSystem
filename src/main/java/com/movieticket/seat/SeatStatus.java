package com.movieticket.seat;

/** AVAILABLE -> TEMPORARILY_LOCKED -> BOOKED, with TEMPORARILY_LOCKED -> AVAILABLE on failure or expiry. */
public enum SeatStatus {
    AVAILABLE,
    TEMPORARILY_LOCKED,
    BOOKED
}
