package com.movieticket.notification;

import com.movieticket.notification.NotificationDtos.NotificationResponse;
import com.movieticket.security.AuthenticatedUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/{userId}")
    public List<NotificationResponse> list(@PathVariable Long userId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.listForUser(userId, caller);
    }

    @PatchMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.markRead(id, caller);
    }
}
