package com.movieticket.theatre;

import com.movieticket.common.exception.ApiException;
import com.movieticket.theatre.TheatreDtos.TheatreRequest;
import com.movieticket.theatre.TheatreDtos.TheatreResponse;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TheatreService {

    private final TheatreRepository theatres;

    public TheatreService(TheatreRepository theatres) {
        this.theatres = theatres;
    }

    @Transactional
    public TheatreResponse create(TheatreRequest r) {
        Theatre t = new Theatre(r.name().trim(), r.city().trim(), r.address().trim(), r.totalScreens());
        return TheatreResponse.from(theatres.save(t));
    }

    @Transactional(readOnly = true)
    public List<TheatreResponse> list(String city) {
        List<Theatre> found = (city == null || city.isBlank())
                ? theatres.findAll(Sort.by("name"))
                : theatres.findByCityIgnoreCaseOrderByName(city.trim());
        return found.stream().map(TheatreResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TheatreResponse get(Long id) {
        return TheatreResponse.from(theatres.findById(id)
                .orElseThrow(() -> ApiException.notFound("THEATRE_NOT_FOUND", "Theatre " + id + " does not exist")));
    }
}
