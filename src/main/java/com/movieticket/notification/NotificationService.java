package com.movieticket.notification;

import com.movieticket.common.exception.ApiException;
import com.movieticket.notification.NotificationDtos.NotificationResponse;
import com.movieticket.security.AuthenticatedUser;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Notifications are stored in MySQL (no real e-mail/SMS is sent). */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Joins the Saga transaction so the notification commits together with the state change. */
    @Transactional
    public void send(Long userId, NotificationType type, String message) {
        notifications.save(new Notification(userId, type, message, LocalDateTime.now(clock)));
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> listForUser(Long userId, AuthenticatedUser caller) {
        requireSelfOrAdmin(userId, caller);
        return notifications.findByUserIdOrderByIdDesc(userId).stream().map(NotificationResponse::from).toList();
    }

    @Transactional
    public NotificationResponse markRead(Long notificationId, AuthenticatedUser caller) {
        Notification n = notifications.findById(notificationId)
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notification " + notificationId + " does not exist"));
        requireSelfOrAdmin(n.getUserId(), caller);
        n.markRead();
        return NotificationResponse.from(n);
    }

    private void requireSelfOrAdmin(Long ownerId, AuthenticatedUser caller) {
        if (!caller.isAdmin() && !caller.id().equals(ownerId)) {
            throw ApiException.forbidden("ACCESS_DENIED", "You can only access your own notifications");
        }
    }
}
