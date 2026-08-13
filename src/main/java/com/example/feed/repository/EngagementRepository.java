package com.example.feed.repository;

import com.example.feed.domain.PostComment;
import com.example.feed.domain.UserProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class EngagementRepository {
    private final JdbcClient jdbc;

    public EngagementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean addLike(String postId, long userId) {
        return jdbc.sql("INSERT IGNORE INTO post_likes(post_id, user_id) VALUES (:postId, :userId)")
                .param("postId", postId).param("userId", userId).update() == 1;
    }

    public boolean removeLike(String postId, long userId) {
        return jdbc.sql("DELETE FROM post_likes WHERE post_id = :postId AND user_id = :userId")
                .param("postId", postId).param("userId", userId).update() == 1;
    }

    public Map<String, EngagementStats> findStats(Collection<String> postIds, long viewerId) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Map<String, EngagementStats> result = new HashMap<>();
        jdbc.sql("""
                SELECT p.id,
                       (SELECT COUNT(*) FROM post_likes l WHERE l.post_id = p.id) like_count,
                       (SELECT COUNT(*) FROM post_comments c
                         WHERE c.post_id = p.id AND c.status = 'ACTIVE') comment_count,
                       EXISTS(SELECT 1 FROM post_likes mine
                               WHERE mine.post_id = p.id AND mine.user_id = :viewerId) liked_by_me
                  FROM posts p WHERE p.id IN (:postIds)
                """).param("postIds", postIds).param("viewerId", viewerId)
                .query((rs, rowNum) -> Map.entry(rs.getString("id"),
                        new EngagementStats(rs.getLong("like_count"), rs.getLong("comment_count"),
                                rs.getBoolean("liked_by_me"))))
                .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    public PostComment addComment(String postId, long authorId, String content) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO post_comments(post_id, author_id, content)
                VALUES (:postId, :authorId, :content)
                """).param("postId", postId).param("authorId", authorId).param("content", content)
                .update(keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("创建评论后未返回主键");
        }
        return findComment(id.longValue()).orElseThrow();
    }

    public Optional<PostComment> findComment(long commentId) {
        return jdbc.sql("""
                SELECT c.id, c.post_id, c.content, c.created_at, c.updated_at,
                       u.id author_id, u.username, u.nickname, u.bio, u.avatar_url
                  FROM post_comments c JOIN users u ON u.id = c.author_id
                 WHERE c.id = :id AND c.status = 'ACTIVE'
                """).param("id", commentId).query(this::mapComment).optional();
    }

    public List<PostComment> findComments(String postId, long afterId, int limit) {
        return jdbc.sql("""
                SELECT c.id, c.post_id, c.content, c.created_at, c.updated_at,
                       u.id author_id, u.username, u.nickname, u.bio, u.avatar_url
                  FROM post_comments c JOIN users u ON u.id = c.author_id
                 WHERE c.post_id = :postId AND c.status = 'ACTIVE' AND c.id > :afterId
                 ORDER BY c.id LIMIT :limit
                """).param("postId", postId).param("afterId", afterId).param("limit", limit)
                .query(this::mapComment).list();
    }

    public boolean deleteComment(long commentId, long userId) {
        return jdbc.sql("""
                UPDATE post_comments c JOIN posts p ON p.id = c.post_id
                   SET c.status = 'DELETED'
                 WHERE c.id = :commentId AND c.status = 'ACTIVE'
                   AND (c.author_id = :userId OR p.author_id = :userId)
                """).param("commentId", commentId).param("userId", userId).update() == 1;
    }

    private PostComment mapComment(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        UserProfile author = new UserProfile(rs.getLong("author_id"), rs.getString("username"),
                rs.getString("nickname"), rs.getString("bio"), rs.getString("avatar_url"));
        return new PostComment(rs.getLong("id"), rs.getString("post_id"), author,
                rs.getString("content"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public record EngagementStats(long likeCount, long commentCount, boolean likedByMe) {
    }
}
