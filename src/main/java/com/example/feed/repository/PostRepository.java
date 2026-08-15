package com.example.feed.repository;

import com.example.feed.domain.AclRule;
import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.Post;
import com.example.feed.domain.PostStatus;
import com.example.feed.domain.Visibility;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class PostRepository {
    private final JdbcClient jdbc;

    public PostRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Post post, String idempotencyKey, String requestFingerprint,
                       FanoutMode deliveryMode) {
        jdbc.sql("""
                INSERT INTO posts(id, author_id, idempotency_key, request_fingerprint,
                                  content, visibility, delivery_mode, status, published_at)
                VALUES (:id, :authorId, :idempotencyKey, :requestFingerprint,
                        :content, :visibility, :deliveryMode, :status, :publishedAt)
                ON DUPLICATE KEY UPDATE id = id
                """)
                .param("id", post.id()).param("authorId", post.authorId())
                .param("content", post.content()).param("visibility", post.visibility().name())
                .param("idempotencyKey", idempotencyKey).param("requestFingerprint", requestFingerprint)
                .param("deliveryMode", deliveryMode.name())
                .param("status", post.status().name()).param("publishedAt", Timestamp.from(post.publishedAt()))
                .update();
    }

    public Optional<FanoutMode> findDeliveryMode(String postId) {
        return jdbc.sql("SELECT delivery_mode FROM posts WHERE id = :postId")
                .param("postId", postId).query(String.class).optional().map(FanoutMode::valueOf);
    }

    public List<String> findRecentPostIdsForModeChange(long authorId, FanoutMode targetMode, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT id FROM posts
                 WHERE author_id = :authorId
                   AND status = 'ACTIVE'
                   AND delivery_mode <> :targetMode
                 ORDER BY published_at DESC, id DESC
                 LIMIT :limit
                """).param("authorId", authorId).param("targetMode", targetMode.name())
                .param("limit", limit).query(String.class).list();
    }

    public int updateDeliveryMode(Collection<String> postIds, FanoutMode targetMode) {
        if (postIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE posts SET delivery_mode = :targetMode
                 WHERE id IN (:postIds) AND delivery_mode <> :targetMode
                """).param("targetMode", targetMode.name()).param("postIds", postIds).update();
    }

    public Optional<IdempotentPost> findByIdempotencyKey(long authorId, String idempotencyKey) {
        return findByIdempotencyKey(authorId, idempotencyKey, false);
    }

    public Optional<IdempotentPost> findByIdempotencyKeyForUpdate(long authorId, String idempotencyKey) {
        return findByIdempotencyKey(authorId, idempotencyKey, true);
    }

    private Optional<IdempotentPost> findByIdempotencyKey(long authorId, String idempotencyKey,
                                                           boolean lockCurrentRead) {
        return jdbc.sql("""
                SELECT id, author_id, content, visibility, status, published_at, request_fingerprint
                  FROM posts
                 WHERE author_id = :authorId AND idempotency_key = :idempotencyKey
                """ + (lockCurrentRead ? " FOR UPDATE" : ""))
                .param("authorId", authorId).param("idempotencyKey", idempotencyKey)
                .query((rs, rowNum) -> new IdempotentPost(mapPost(rs, rowNum),
                        rs.getString("request_fingerprint"))).optional();
    }

    public void insertAcl(String postId, Collection<Long> targetIds, AclRule rule) {
        for (Long targetId : targetIds) {
            jdbc.sql("""
                    INSERT INTO post_acl(post_id, target_user_id, rule_type)
                    VALUES (:postId, :targetId, :rule)
                    """).param("postId", postId).param("targetId", targetId)
                    .param("rule", rule.name()).update();
        }
    }

    public Optional<Post> findById(String postId) {
        return jdbc.sql("""
                SELECT id, author_id, content, visibility, status, published_at
                  FROM posts WHERE id = :id
                """).param("id", postId).query(this::mapPost).optional();
    }

    public Map<String, Post> findByIds(Collection<String> postIds) {
        if (postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Post> result = new HashMap<>();
        jdbc.sql("""
                SELECT id, author_id, content, visibility, status, published_at
                  FROM posts WHERE id IN (:ids)
                """).param("ids", postIds).query(this::mapPost).list()
                .forEach(post -> result.put(post.id(), post));
        return result;
    }

    public Map<String, Set<Long>> findAclTargets(Collection<String> postIds, AclRule rule) {
        if (postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Set<Long>> result = new HashMap<>();
        jdbc.sql("""
                SELECT post_id, target_user_id FROM post_acl
                 WHERE post_id IN (:ids) AND rule_type = :rule
                """).param("ids", postIds).param("rule", rule.name())
                .query((rs, rowNum) -> Map.entry(rs.getString("post_id"), rs.getLong("target_user_id")))
                .list().forEach(entry -> result.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>())
                        .add(entry.getValue()));
        return result;
    }

    public int markDeleted(String postId, long authorId) {
        return jdbc.sql("""
                UPDATE posts SET status = 'DELETED'
                 WHERE id = :postId AND author_id = :authorId AND status = 'ACTIVE'
                """).param("postId", postId).param("authorId", authorId).update();
    }

    private Post mapPost(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Post(rs.getString("id"), rs.getLong("author_id"), rs.getString("content"),
                Visibility.valueOf(rs.getString("visibility")), PostStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("published_at").toInstant());
    }

    public record IdempotentPost(Post post, String requestFingerprint) {
    }
}
