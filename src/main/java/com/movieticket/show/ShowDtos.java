package com.movieticket.show;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class ShowDtos {

    private ShowDtos() {
    }

    /** rows x seatsPerRow seats (A1..) are generated automatically; defaults are 5 x 10. */
    public record CreateShowRequest(
            @NotNull(message = "movieId is required") Long movieId,
            @NotNull(message = "theatreId is required") Long theatreId,
            @NotNull(message = "screenNo is required") @Min(value = 1, message = "screenNo must be at least 1") Integer screenNo,
            @NotNull(message = "showTime is required") @Future(message = "showTime must be in the future") LocalDateTime showTime,
            @NotNull(message = "price is required") @DecimalMin(value = "0.01", message = "price must be greater than 0")
            @Digits(integer = 8, fraction = 2, message = "price must have at most 2 decimal places") BigDecimal price,
            @Min(value = 1, message = "rows must be at least 1") @Max(value = 26, message = "rows must be at most 26") Integer rows,
            @Min(value = 1, message = "seatsPerRow must be at least 1") @Max(value = 30, message = "seatsPerRow must be at most 30") Integer seatsPerRow) {
    }

    public record ShowResponse(Long id, Long movieId, String movieTitle, Long theatreId, String theatreName, String city,
                               int screenNo, LocalDateTime showTime, BigDecimal price, long totalSeats, long availableSeats) {
    }
}
