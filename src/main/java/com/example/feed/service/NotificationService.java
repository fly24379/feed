package com.example.feed.service;

import com.example.feed.api.NotFoundException;
import com.example.feed.domain.NotificationItem;
import com.example.feed.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NotificationService {
    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public NotificationPage list(long userId, boolean unreadOnly, Long beforeId, Integer requestedSize) {
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 100));
        long cursor = beforeId == null ? Long.MAX_VALUE : beforeId;
        List<NotificationItem> loaded = notifications.findPage(userId, unreadOnly, cursor, size + 1);
        boolean hasMore = loaded.size() > size;
        List<NotificationItem> items = hasMore ? List.copyOf(loaded.subList(0, size)) : List.copyOf(loaded);
        Long nextBeforeId = hasMore ? items.getLast().id() : null;
        return new NotificationPage(items, nextBeforeId, hasMore, notifications.countUnread(userId));
    }

    @Transactional
    public void markRead(long userId, long notificationId) {
        if (!notifications.markRead(userId, notificationId)) {
            throw new NotFoundException("通知不存在: " + notificationId);
        }
    }

    @Transactional
    public int markAllRead(long userId) {
        return notifications.markAllRead(userId);
    }

    public record NotificationPage(List<NotificationItem> items, Long nextBeforeId,
                                   boolean hasMore, long unreadCount) {
    }
}
