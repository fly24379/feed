package com.example.feed.repository;

import com.example.feed.domain.UserProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

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
        follow(userId, friendId);
        follow(friendId, userId);
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
        jdbc.sql("""
                DELETE FROM follows
                 WHERE (follower_id = :first AND followee_id = :second)
                    OR (follower_id = :second AND followee_id = :first)
                """).param("first", blockerId).param("second", blockedId).update();
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

    public boolean follow(long followerId, long followeeId) {
        return jdbc.sql("""
                INSERT IGNORE INTO follows(follower_id, followee_id)
                VALUES (:follower, :followee)
                """).param("follower", followerId).param("followee", followeeId).update() > 0;
    }

    public boolean unfollow(long followerId, long followeeId) {
        return jdbc.sql("""
                DELETE FROM follows
                 WHERE follower_id = :follower AND followee_id = :followee
                """).param("follower", followerId).param("followee", followeeId).update() > 0;
    }

    public boolean isFollowing(long followerId, long followeeId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM follows
                 WHERE follower_id = :follower AND followee_id = :followee
                """).param("follower", followerId).param("followee", followeeId)
                .query(Integer.class).single() > 0;
    }

    public boolean isFollowingUnblocked(long followerId, long followeeId) {
        if (followerId == followeeId) {
            return true;
        }
        return jdbc.sql("""
                SELECT COUNT(*) FROM follows f
                 WHERE f.follower_id = :follower AND f.followee_id = :followee
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = :follower AND b.blocked_id = :followee)
                           OR (b.blocker_id = :followee AND b.blocked_id = :follower)
                   )
                """).param("follower", followerId).param("followee", followeeId)
                .query(Integer.class).single() > 0;
    }

    public boolean isActiveFriend(long userId, long otherId) {
        long low = Math.min(userId, otherId);
        long high = Math.max(userId, otherId);
        return jdbc.sql("""
                SELECT COUNT(*) FROM friendships
                 WHERE user_low = :low AND user_high = :high AND status = 'ACTIVE'
                """).param("low", low).param("high", high).query(Integer.class).single() > 0;
    }

    public boolean isBlockedEitherDirection(long userId, long otherId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM blocks
                 WHERE (blocker_id = :userId AND blocked_id = :otherId)
                    OR (blocker_id = :otherId AND blocked_id = :userId)
                """).param("userId", userId).param("otherId", otherId)
                .query(Integer.class).single() > 0;
    }

    public List<UserProfile> findFriends(long userId) {
        return jdbc.sql("""
                SELECT u.id, u.username, u.nickname, u.bio, u.avatar_url
                  FROM friendships f
                  JOIN users u ON u.id = CASE WHEN f.user_low = :userId THEN f.user_high ELSE f.user_low END
                 WHERE f.status = 'ACTIVE' AND (f.user_low = :userId OR f.user_high = :userId)
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = :userId AND b.blocked_id = u.id)
                           OR (b.blocked_id = :userId AND b.blocker_id = u.id)
                   )
                 ORDER BY u.username
                """).param("userId", userId).query(this::mapProfile).list();
    }

    public List<UserProfile> findBlockedUsers(long userId) {
        return jdbc.sql("""
                SELECT u.id, u.username, u.nickname, u.bio, u.avatar_url
                  FROM blocks b JOIN users u ON u.id = b.blocked_id
                 WHERE b.blocker_id = :userId
                 ORDER BY b.created_at DESC
                """).param("userId", userId).query(this::mapProfile).list();
    }

    public List<UserProfile> findFollowing(long userId, long beforeUserId, int limit) {
        return jdbc.sql("""
                SELECT u.id, u.username, u.nickname, u.bio, u.avatar_url
                  FROM follows f JOIN users u ON u.id = f.followee_id
                 WHERE f.follower_id = :userId
                   AND f.followee_id < :beforeUserId
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = :userId AND b.blocked_id = u.id)
                           OR (b.blocker_id = u.id AND b.blocked_id = :userId)
                   )
                 ORDER BY f.followee_id DESC
                 LIMIT :limit
                """).param("userId", userId).param("beforeUserId", beforeUserId)
                .param("limit", limit).query(this::mapProfile).list();
    }

    public List<UserProfile> findFollowers(long userId, long beforeUserId, int limit) {
        return jdbc.sql("""
                SELECT u.id, u.username, u.nickname, u.bio, u.avatar_url
                  FROM follows f JOIN users u ON u.id = f.follower_id
                 WHERE f.followee_id = :userId
                   AND f.follower_id < :beforeUserId
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = :userId AND b.blocked_id = u.id)
                           OR (b.blocker_id = u.id AND b.blocked_id = :userId)
                   )
                 ORDER BY f.follower_id DESC
                 LIMIT :limit
                """).param("userId", userId).param("beforeUserId", beforeUserId)
                .param("limit", limit).query(this::mapProfile).list();
    }

    public FollowStats findFollowStats(long viewerId, long userId) {
        return jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM follows WHERE follower_id = :userId) AS following_count,
                       (SELECT COUNT(*) FROM follows WHERE followee_id = :userId) AS follower_count,
                       EXISTS(SELECT 1 FROM follows
                               WHERE follower_id = :viewerId AND followee_id = :userId) AS followed_by_viewer,
                       EXISTS(SELECT 1 FROM follows
                               WHERE follower_id = :userId AND followee_id = :viewerId) AS follows_viewer
                """).param("viewerId", viewerId).param("userId", userId)
                .query((rs, rowNum) -> new FollowStats(
                        rs.getLong("following_count"), rs.getLong("follower_count"),
                        rs.getBoolean("followed_by_viewer"), rs.getBoolean("follows_viewer")))
                .single();
    }

    public int backfillRecentPushPosts(long followerId, long followeeId, int limit) {
        if (limit <= 0) {
            return 0;
        }
        return jdbc.sql("""
                INSERT IGNORE INTO feed_inbox(owner_id, post_id, author_id, published_at)
                SELECT :follower, p.id, p.author_id, p.published_at
                  FROM posts p
                 WHERE p.author_id = :followee AND p.delivery_mode = 'PUSH'
                   AND p.status = 'ACTIVE' AND p.visibility <> 'ONLY_ME'
                   AND EXISTS (SELECT 1 FROM follows f
                                WHERE f.follower_id = :follower AND f.followee_id = :followee)
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = :follower AND b.blocked_id = :followee)
                           OR (b.blocker_id = :followee AND b.blocked_id = :follower)
                   )
                   AND (p.visibility <> 'INCLUDE_LIST' OR EXISTS (
                       SELECT 1 FROM post_acl a
                        WHERE a.post_id = p.id AND a.rule_type = 'ALLOW'
                          AND a.target_user_id = :follower
                   ))
                   AND (p.visibility <> 'EXCLUDE_LIST' OR NOT EXISTS (
                       SELECT 1 FROM post_acl a
                        WHERE a.post_id = p.id AND a.rule_type = 'DENY'
                          AND a.target_user_id = :follower
                   ))
                 ORDER BY p.published_at DESC, p.id DESC
                 LIMIT :limit
                """).param("follower", followerId).param("followee", followeeId)
                .param("limit", limit).update();
    }

    public Set<Long> findAccessibleAuthors(long viewerId, Collection<Long> authorIds) {
        Set<Long> result = new HashSet<>();
        result.add(viewerId);
        if (authorIds.isEmpty()) {
            return result;
        }
        result.addAll(jdbc.sql("""
                SELECT f.followee_id AS author_id
                  FROM follows f
                 WHERE f.follower_id = :viewer
                   AND f.followee_id IN (:authors)
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = :viewer AND b.blocked_id = f.followee_id)
                           OR (b.blocked_id = :viewer AND b.blocker_id = f.followee_id)
                   )
                """)
                .param("viewer", viewerId)
                .param("authors", authorIds)
                .query(Long.class).list());
        return result;
    }

    public List<ConnectionCount> findConnectionCountsAfter(long afterUserId, int limit) {
        return jdbc.sql("""
                SELECT u.id,
                       (SELECT COUNT(*) FROM follows f
                         WHERE f.followee_id = u.id) AS follower_count
                  FROM users u
                 WHERE u.id > :afterUserId
                 ORDER BY u.id
                 LIMIT :limit
                """).param("afterUserId", afterUserId).param("limit", limit)
                .query((rs, rowNum) -> new ConnectionCount(
                        rs.getLong("id"), rs.getLong("follower_count"))).list();
    }

    private UserProfile mapProfile(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserProfile(rs.getLong("id"), rs.getString("username"), rs.getString("nickname"),
                rs.getString("bio"), rs.getString("avatar_url"));
    }

    public record ConnectionCount(long userId, long followerCount) {
    }

    public record FollowStats(long followingCount, long followerCount,
                              boolean followedByViewer, boolean followsViewer) {
    }
}
