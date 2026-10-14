package com.movieticket.seat;

import com.movieticket.seat.SeatDtos.AddSeatsRequest;
import com.movieticket.seat.SeatDtos.SeatResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Only read access and admin seat creation are exposed. Lock / confirm / release are deliberately NOT
 * REST endpoints: they are internal steps of the booking Saga.
 */
@RestController
@RequestMapping("/api/shows/{showId}/seats")
public class SeatController {

    private final SeatService service;

    public SeatController(SeatService service) {
        this.service = service;
    }

    @GetMapping
    public List<SeatResponse> list(@PathVariable Long showId) {
        return service.listForShow(showId);
    }

    @PostMapping
    public ResponseEntity<List<SeatResponse>> add(@PathVariable Long showId, @Valid @RequestBody AddSeatsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addSeats(showId, request.seatNumbers()));
    }
}
