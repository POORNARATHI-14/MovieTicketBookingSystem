
package com.movieticket.admin;

import com.movieticket.booking.BookingDtos.BookingResponse;
import com.movieticket.booking.BookingService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final BookingService bookingService;

    public AdminController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GetMapping("/bookings")
    public List<BookingResponse> getAllBookings(
            @RequestParam(defaultValue = "200") int limit) {

        int safeLimit = Math.max(1, Math.min(limit, 500));
        return bookingService.listAll(safeLimit);
    }
}
