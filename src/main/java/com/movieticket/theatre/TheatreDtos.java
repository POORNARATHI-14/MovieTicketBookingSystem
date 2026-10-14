package com.movieticket.theatre;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TheatreDtos {

    private TheatreDtos() {
    }

    public record TheatreRequest(
            @NotBlank(message = "name is required") @Size(max = 120) String name,
            @NotBlank(message = "city is required") @Size(max = 80) String city,
            @NotBlank(message = "address is required") @Size(max = 255) String address,
            @NotNull(message = "totalScreens is required") @Min(value = 1, message = "totalScreens must be at least 1")
            @Max(value = 50, message = "totalScreens must be at most 50") Integer totalScreens) {
    }

    public record TheatreResponse(Long id, String name, String city, String address, int totalScreens) {
        public static TheatreResponse from(Theatre t) {
            return new TheatreResponse(t.getId(), t.getName(), t.getCity(), t.getAddress(), t.getTotalScreens());
        }
    }
}
