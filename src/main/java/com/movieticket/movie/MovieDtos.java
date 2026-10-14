package com.movieticket.movie;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class MovieDtos {

    private MovieDtos() {
    }

    public record MovieRequest(
            @NotBlank(message = "title is required") @Size(max = 150) String title,
            @NotBlank(message = "genre is required") @Size(max = 60) String genre,
            @NotNull(message = "durationMin is required") @Min(value = 1, message = "durationMin must be at least 1")
            @Max(value = 600, message = "durationMin must be at most 600") Integer durationMin,
            @NotBlank(message = "language is required") @Size(max = 40) String language,
            @Size(max = 2000) String description) {
    }

    public record MovieResponse(Long id, String title, String genre, int durationMin, String language, String description) {
        public static MovieResponse from(Movie m) {
            return new MovieResponse(m.getId(), m.getTitle(), m.getGenre(), m.getDurationMin(), m.getLanguage(), m.getDescription());
        }
    }
}
