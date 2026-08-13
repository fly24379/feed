package com.example.feed.domain;

import java.time.Instant;

public record NotificationItem(long id, String type, UserProfile actor, String entityType,
                               String entityId, String message, Instant readAt, Instant createdAt) {
    public boolean read() {
        return readAt != null;
    }
}
