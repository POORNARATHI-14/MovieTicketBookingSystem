package com.movieticket.notification;

import java.time.LocalDateTime;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationResponse(Long id, Long userId, NotificationType type, String message, boolean read,
                                       LocalDateTime createdAt) {
        public static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getUserId(), n.getType(), n.getMessage(), n.isRead(), n.getCreatedAt());
        }
    }
}
