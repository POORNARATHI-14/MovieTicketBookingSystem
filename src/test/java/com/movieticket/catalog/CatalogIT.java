package com.movieticket.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movieticket.show.Show;
import com.movieticket.support.AbstractIntegrationTest;
import com.movieticket.user.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CatalogIT extends AbstractIntegrationTest {

    private static final String MOVIE = "{\"title\":\"Interstellar\",\"genre\":\"Sci-Fi\",\"durationMin\":169,\"language\":\"English\",\"description\":\"Space\"}";
    private static final String THEATRE = "{\"name\":\"Grand\",\"city\":\"Chennai\",\"address\":\"12 Anna Salai\",\"totalScreens\":2}";

    private long create(String url, String token, String body) throws Exception {
        String res = mvc.perform(post(url).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    private String showJson(long movieId, long theatreId, int screenNo, LocalDateTime time) {
        return "{\"movieId\":" + movieId + ",\"theatreId\":" + theatreId + ",\"screenNo\":" + screenNo
                + ",\"showTime\":\"" + time.withNano(0) + "\",\"price\":180.50,\"rows\":2,\"seatsPerRow\":4}";
    }

    @Test
    @DisplayName("Admin creates movie, theatre and show: relationships and generated seats are stored in MySQL")
    void createMovieTheatreShow() throws Exception {
        String admin = tokenOf(createAdmin());
        long movieId = create("/api/movies", admin, MOVIE);
        long theatreId = create("/api/theatres", admin, THEATRE);
        long showId = create("/api/shows", admin, showJson(movieId, theatreId, 1, LocalDateTime.now().plusDays(3)));

        Show show = shows.findById(showId).orElseThrow();
        assertThat(show.getMovieId()).isEqualTo(movieId);
        assertThat(show.getTheatreId()).isEqualTo(theatreId);
        assertThat(show.getPrice()).isEqualByComparingTo("180.50");
        assertThat(seats.countByShowId(showId)).isEqualTo(8); // 2 rows x 4 seats

        mvc.perform(get("/api/shows/" + showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieTitle").value("Interstellar"))
                .andExpect(jsonPath("$.theatreName").value("Grand"))
                .andExpect(jsonPath("$.availableSeats").value(8));
        mvc.perform(get("/api/shows/" + showId + "/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].seatNumber").value("A1"))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("Only ADMIN may change the catalogue; browsing is public")
    void authorization() throws Exception {
        User normal = createUser("user@test.local");
        mvc.perform(post("/api/movies").contentType(MediaType.APPLICATION_JSON).content(MOVIE))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/movies").header("Authorization", "Bearer " + tokenOf(normal))
                        .contentType(MediaType.APPLICATION_JSON).content(MOVIE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/movies")).andExpect(status().isOk());
        mvc.perform(get("/api/theatres")).andExpect(status().isOk());
        mvc.perform(get("/api/shows")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Movie update, filter and delete; a movie with shows cannot be deleted")
    void movieCrud() throws Exception {
        String admin = tokenOf(createAdmin());
        long movieId = create("/api/movies", admin, MOVIE);

        mvc.perform(put("/api/movies/" + movieId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Interstellar (IMAX)\",\"genre\":\"Sci-Fi\",\"durationMin\":170,\"language\":\"English\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Interstellar (IMAX)"));
        mvc.perform(get("/api/movies").param("genre", "sci-fi"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/movies").param("genre", "Horror"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        long theatreId = create("/api/theatres", admin, THEATRE);
        create("/api/shows", admin, showJson(movieId, theatreId, 1, LocalDateTime.now().plusDays(3)));
        mvc.perform(delete("/api/movies/" + movieId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOVIE_HAS_SHOWS"));

        long other = create("/api/movies", admin, MOVIE.replace("Interstellar", "Other"));
        mvc.perform(delete("/api/movies/" + other).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/movies/" + other)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Invalid show requests: unknown movie/theatre (404), bad screen, past time, bad price (400), duplicate slot (409)")
    void showValidation() throws Exception {
        String admin = tokenOf(createAdmin());
        long movieId = create("/api/movies", admin, MOVIE);
        long theatreId = create("/api/theatres", admin, THEATRE);
        LocalDateTime when = LocalDateTime.now().plusDays(4);

        mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(showJson(99999, theatreId, 1, when)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));
        mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(showJson(movieId, 99999, 1, when)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("THEATRE_NOT_FOUND"));
        mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(showJson(movieId, theatreId, 3, when)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SCREEN"));
        mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(showJson(movieId, theatreId, 1, LocalDateTime.now().minusDays(1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.showTime").exists());
        mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(showJson(movieId, theatreId, 1, when).replace("180.50", "-5")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.price").exists());

        create("/api/shows", admin, showJson(movieId, theatreId, 1, when));
        mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(showJson(movieId, theatreId, 1, when)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOW_SLOT_TAKEN"));
        assertThat(shows.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Unknown show on the seat map gives 404; unknown ids and wrong types are handled cleanly")
    void notFoundAndBadParams() throws Exception {
        mvc.perform(get("/api/shows/99999/seats")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOW_NOT_FOUND"));
        mvc.perform(get("/api/shows/abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized()); // anything unknown needs a token
    }
}
