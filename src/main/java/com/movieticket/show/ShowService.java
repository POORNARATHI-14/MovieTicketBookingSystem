package com.movieticket.show;

import com.movieticket.common.exception.ApiException;
import com.movieticket.movie.Movie;
import com.movieticket.movie.MovieRepository;
import com.movieticket.seat.SeatService;
import com.movieticket.show.ShowDtos.CreateShowRequest;
import com.movieticket.show.ShowDtos.ShowResponse;
import com.movieticket.theatre.Theatre;
import com.movieticket.theatre.TheatreRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);
    private static final int DEFAULT_ROWS = 5;
    private static final int DEFAULT_SEATS_PER_ROW = 10;

    private final ShowRepository shows;
    private final MovieRepository movies;
    private final TheatreRepository theatres;
    private final SeatService seatService;

    public ShowService(ShowRepository shows, MovieRepository movies, TheatreRepository theatres, SeatService seatService) {
        this.shows = shows;
        this.movies = movies;
        this.theatres = theatres;
        this.seatService = seatService;
    }

    /** Creates the show and its seats atomically: either both exist afterwards or neither. */
    @Transactional
    public ShowResponse create(CreateShowRequest r) {
        Movie movie = movies.findById(r.movieId())
                .orElseThrow(() -> ApiException.notFound("MOVIE_NOT_FOUND", "Movie " + r.movieId() + " does not exist"));
        Theatre theatre = theatres.findById(r.theatreId())
                .orElseThrow(() -> ApiException.notFound("THEATRE_NOT_FOUND", "Theatre " + r.theatreId() + " does not exist"));
        if (r.screenNo() > theatre.getTotalScreens()) {
            throw ApiException.badRequest("INVALID_SCREEN",
                    "Theatre '" + theatre.getName() + "' has only " + theatre.getTotalScreens() + " screens");
        }
        if (shows.existsByTheatreIdAndScreenNoAndShowTime(theatre.getId(), r.screenNo(), r.showTime())) {
            throw slotTaken();
        }
        try {
            Show show = shows.saveAndFlush(new Show(movie.getId(), theatre.getId(), r.screenNo(), r.showTime(), r.price()));
            int rows = r.rows() != null ? r.rows() : DEFAULT_ROWS;
            int perRow = r.seatsPerRow() != null ? r.seatsPerRow() : DEFAULT_SEATS_PER_ROW;
            seatService.createSeatsForShow(show.getId(), rows, perRow);
            log.info("Created show id={} with {} seats", show.getId(), rows * perRow);
            return toResponse(show, movie, theatre);
        } catch (DataIntegrityViolationException ex) {
            throw slotTaken();
        }
    }

    @Transactional(readOnly = true)
    public List<ShowResponse> list(Long movieId, Long theatreId, LocalDate date) {
        LocalDateTime from = date != null ? date.atStartOfDay() : null;
        LocalDateTime to = date != null ? date.plusDays(1).atStartOfDay() : null;
        return shows.search(movieId, theatreId, from, to).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ShowResponse get(Long id) {
        return toResponse(findShow(id));
    }

    public Show findShow(Long id) {
        return shows.findById(id)
                .orElseThrow(() -> ApiException.notFound("SHOW_NOT_FOUND", "Show " + id + " does not exist"));
    }

    private ShowResponse toResponse(Show show) {
        Movie movie = movies.findById(show.getMovieId()).orElseThrow();
        Theatre theatre = theatres.findById(show.getTheatreId()).orElseThrow();
        return toResponse(show, movie, theatre);
    }

    private ShowResponse toResponse(Show show, Movie movie, Theatre theatre) {
        return new ShowResponse(show.getId(), movie.getId(), movie.getTitle(), theatre.getId(), theatre.getName(),
                theatre.getCity(), show.getScreenNo(), show.getShowTime(), show.getPrice(),
                seatService.countAll(show.getId()), seatService.countAvailable(show.getId()));
    }

    private ApiException slotTaken() {
        return ApiException.conflict("SHOW_SLOT_TAKEN", "That screen already has a show at that time");
    }
}
