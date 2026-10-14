package com.movieticket.theatre;

import com.movieticket.theatre.TheatreDtos.TheatreRequest;
import com.movieticket.theatre.TheatreDtos.TheatreResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/theatres")
public class TheatreController {

    private final TheatreService service;

    public TheatreController(TheatreService service) {
        this.service = service;
    }

    @GetMapping
    public List<TheatreResponse> list(@RequestParam(required = false) String city) {
        return service.list(city);
    }

    @PostMapping
    public ResponseEntity<TheatreResponse> create(@Valid @RequestBody TheatreRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/{id}")
    public TheatreResponse get(@PathVariable Long id) {
        return service.get(id);
    }
}
