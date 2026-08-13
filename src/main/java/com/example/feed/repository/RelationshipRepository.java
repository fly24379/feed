package com.example.feed.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Repository
public class RelationshipRepository {
    private final JdbcClient jdbc;

    public RelationshipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void addFriend(long userId, long friendId) {
        long low = Math.min(userId, friendId);
        long high = Math.max(userId, friendId);
        jdbc.sql("""
                INSERT INTO friendships(user_low, user_high, status)
                VALUES (:low, :high, 'ACTIVE')
                ON DUPLICATE KEY UPDATE status = 'ACTIVE'
                """).param("low", low).param("high", high).update();
    }

    public void removeFriend(long userId, long friendId) {
        long low = Math.min(userId, friendId);
        long high = Math.max(userId, friendId);
        jdbc.sql("UPDATE friendships SET status = 'DELETED' WHERE user_low = :low AND user_high = :high")
                .param("low", low).param("high", high).update();
    }

    public void block(long blockerId, long blockedId) {
        jdbc.sql("INSERT IGNORE INTO blocks(blocker_id, blocked_id) VALUES (:blocker, :blocked)")
                .param("blocker", blockerId).param("blocked", blockedId).update();
    }

    public void unblock(long blockerId, long blockedId) {
        jdbc.sql("DELETE FROM blocks WHERE blocker_id = :blocker AND blocked_id = :blocked")
                .param("blocker", blockerId).param("blocked", blockedId).update();
    }

    public boolean isActiveUnblockedFriend(long userId, long otherId) {
        if (userId == otherId) {
            return true;
        }
        long low = Math.min(userId, otherId);
        long high = Math.max(userId, otherId);
        return jdbc.sql("""
                SELECT COUNT(*)
                  FROM friendships f
                 WHERE f.user_low = :low AND f.user_high = :high AND f.status = 'ACTIVE'
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = :userId AND b.blocked_id = :otherId)
                           OR (b.blocker_id = :otherId AND b.blocked_id = :userId)
                   )
                """)
                .param("low", low).param("high", high)
                .param("userId", userId).param("otherId", otherId)
                .query(Integer.class).single() > 0;
    }

    public Set<Long> findAccessibleAuthors(long viewerId, Collection<Long> authorIds) {
        Set<Long> result = new HashSet<>();
        result.add(viewerId);
        if (authorIds.isEmpty()) {
            return result;
        }
        result.addAll(jdbc.sql("""
                SELECT CASE WHEN f.user_low = :viewer THEN f.user_high ELSE f.user_low END AS friend_id
                  FROM friendships f
                 WHERE f.status = 'ACTIVE'
                   AND (f.user_low = :viewer OR f.user_high = :viewer)
                   AND (CASE WHEN f.user_low = :viewer THEN f.user_high ELSE f.user_low END) IN (:authors)
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = :viewer AND b.blocked_id =
                                  CASE WHEN f.user_low = :viewer THEN f.user_high ELSE f.user_low END)
                           OR (b.blocked_id = :viewer AND b.blocker_id =
                                  CASE WHEN f.user_low = :viewer THEN f.user_high ELSE f.user_low END)
                   )
                """)
                .param("viewer", viewerId)
                .param("authors", authorIds)
                .query(Long.class).list());
        return result;
    }
}
