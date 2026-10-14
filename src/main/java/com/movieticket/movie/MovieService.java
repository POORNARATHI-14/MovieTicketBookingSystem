package com.movieticket.movie;

import com.movieticket.common.exception.ApiException;
import com.movieticket.movie.MovieDtos.MovieRequest;
import com.movieticket.movie.MovieDtos.MovieResponse;
import com.movieticket.show.ShowRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MovieService {

    private final MovieRepository movies;
    private final ShowRepository shows;

    public MovieService(MovieRepository movies, ShowRepository shows) {
        this.movies = movies;
        this.shows = shows;
    }

    @Transactional
    public MovieResponse create(MovieRequest r) {
        Movie m = new Movie(r.title().trim(), r.genre().trim(), r.durationMin(), r.language().trim(), r.description());
        return MovieResponse.from(movies.save(m));
    }

    @Transactional(readOnly = true)
    public List<MovieResponse> list(String genre, String language, String title) {
        return movies.search(blankToNull(genre), blankToNull(language), blankToNull(title))
                .stream().map(MovieResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MovieResponse get(Long id) {
        return MovieResponse.from(find(id));
    }

    @Transactional
    public MovieResponse update(Long id, MovieRequest r) {
        Movie m = find(id);
        m.update(r.title().trim(), r.genre().trim(), r.durationMin(), r.language().trim(), r.description());
        return MovieResponse.from(movies.save(m));
    }

    @Transactional
    public void delete(Long id) {
        Movie m = find(id);
        if (shows.existsByMovieId(id)) {
            throw ApiException.conflict("MOVIE_HAS_SHOWS", "Movie " + id + " has shows scheduled and cannot be deleted");
        }
        movies.delete(m);
    }

    private Movie find(Long id) {
        return movies.findById(id)
                .orElseThrow(() -> ApiException.notFound("MOVIE_NOT_FOUND", "Movie " + id + " does not exist"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
