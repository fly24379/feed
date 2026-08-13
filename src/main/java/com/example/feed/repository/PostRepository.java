package com.example.feed.repository;

import com.example.feed.domain.AclRule;
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

    public void insert(Post post) {
        jdbc.sql("""
                INSERT INTO posts(id, author_id, content, visibility, status, published_at)
                VALUES (:id, :authorId, :content, :visibility, :status, :publishedAt)
                """)
                .param("id", post.id()).param("authorId", post.authorId())
                .param("content", post.content()).param("visibility", post.visibility().name())
                .param("status", post.status().name()).param("publishedAt", Timestamp.from(post.publishedAt()))
                .update();
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
}
