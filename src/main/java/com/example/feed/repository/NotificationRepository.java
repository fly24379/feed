package com.example.feed.repository;

import com.example.feed.domain.NotificationItem;
import com.example.feed.domain.UserProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class NotificationRepository {
    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void add(long userId, String type, Long actorId, String entityType,
                    String entityId, String message) {
        if (actorId != null && actorId == userId) {
            return;
        }
        jdbc.sql("""
                INSERT INTO notifications(user_id, type, actor_id, entity_type, entity_id, message)
                VALUES (:userId, :type, :actorId, :entityType, :entityId, :message)
                """).param("userId", userId).param("type", type).param("actorId", actorId)
                .param("entityType", entityType).param("entityId", entityId)
                .param("message", message).update();
    }

    public List<NotificationItem> findPage(long userId, boolean unreadOnly, long beforeId, int limit) {
        return jdbc.sql("""
                SELECT n.id, n.type, n.entity_type, n.entity_id, n.message, n.read_at, n.created_at,
                       actor.id actor_id, actor.username actor_username, actor.nickname actor_nickname,
                       actor.bio actor_bio, actor.avatar_url actor_avatar_url
                  FROM notifications n LEFT JOIN users actor ON actor.id = n.actor_id
                 WHERE n.user_id = :userId AND n.id < :beforeId
                   AND (:unreadOnly = FALSE OR n.read_at IS NULL)
                 ORDER BY n.id DESC LIMIT :limit
                """).param("userId", userId).param("beforeId", beforeId)
                .param("unreadOnly", unreadOnly).param("limit", limit).query(this::map).list();
    }

    public long countUnread(long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM notifications WHERE user_id = :userId AND read_at IS NULL")
                .param("userId", userId).query(Long.class).single();
    }

    public boolean markRead(long userId, long notificationId) {
        return jdbc.sql("""
                UPDATE notifications SET read_at = CURRENT_TIMESTAMP(6)
                 WHERE id = :id AND user_id = :userId
                """).param("id", notificationId).param("userId", userId).update() == 1;
    }

    public int markAllRead(long userId) {
        return jdbc.sql("""
                UPDATE notifications SET read_at = CURRENT_TIMESTAMP(6)
                 WHERE user_id = :userId AND read_at IS NULL
                """).param("userId", userId).update();
    }

    private NotificationItem map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Long actorId = (Long) rs.getObject("actor_id");
        UserProfile actor = actorId == null ? null : new UserProfile(actorId,
                rs.getString("actor_username"), rs.getString("actor_nickname"),
                rs.getString("actor_bio"), rs.getString("actor_avatar_url"));
        Timestamp readAt = rs.getTimestamp("read_at");
        return new NotificationItem(rs.getLong("id"), rs.getString("type"), actor,
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("message"),
                readAt == null ? null : readAt.toInstant(), rs.getTimestamp("created_at").toInstant());
    }
}
