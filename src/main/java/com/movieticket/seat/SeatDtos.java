package com.movieticket.seat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class SeatDtos {

    private SeatDtos() {
    }

    public record SeatResponse(Long id, Long showId, String seatNumber, SeatStatus status, LocalDateTime lockedUntil) {
        public static SeatResponse from(Seat s) {
            return new SeatResponse(s.getId(), s.getShowId(), s.getSeatNumber(), s.getStatus(), s.getLockedUntil());
        }
    }

    public record AddSeatsRequest(
            @NotEmpty(message = "seatNumbers must contain at least one seat")
            @Size(max = 200, message = "at most 200 seats can be added at once")
            List<@NotBlank(message = "seat numbers must not be blank") @Size(max = 10, message = "seat numbers are at most 10 characters") String> seatNumbers) {
    }
}
