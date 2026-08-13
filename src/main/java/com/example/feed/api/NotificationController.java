package com.example.feed.api;

import com.example.feed.security.CurrentUser;
import com.example.feed.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;
    private final CurrentUser currentUser;

    public NotificationController(NotificationService notifications, CurrentUser currentUser) {
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    @GetMapping
    public NotificationService.NotificationPage list(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                      @RequestParam(required = false) Long beforeId,
                                                      @RequestParam(required = false) Integer size) {
        return notifications.list(currentUser.id(jwt), unreadOnly, beforeId, size);
    }

    @PatchMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable long notificationId) {
        notifications.markRead(currentUser.id(jwt), notificationId);
    }

    @PatchMapping("/read-all")
    public ReadAllResponse markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return new ReadAllResponse(notifications.markAllRead(currentUser.id(jwt)));
    }

    public record ReadAllResponse(int updatedCount) {
    }
}
