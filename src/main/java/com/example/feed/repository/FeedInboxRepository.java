package com.example.feed.repository;

import com.example.feed.domain.FeedCandidate;
import com.example.feed.domain.FeedCursor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class FeedInboxRepository {
    private final JdbcClient jdbc;

    public FeedInboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSelf(long ownerId, String postId, Instant publishedAt) {
        jdbc.sql("""
                INSERT IGNORE INTO feed_inbox(owner_id, post_id, author_id, published_at)
                VALUES (:ownerId, :postId, :ownerId, :publishedAt)
                """).param("ownerId", ownerId).param("postId", postId)
                .param("publishedAt", Timestamp.from(publishedAt)).update();
    }

    public List<FeedCandidate> findPage(long ownerId, FeedCursor cursor, int limit) {
        if (cursor == null) {
            return jdbc.sql("""
                    SELECT post_id, published_at FROM feed_inbox
                     WHERE owner_id = :ownerId
                     ORDER BY published_at DESC, post_id DESC
                     LIMIT :limit
                    """).param("ownerId", ownerId).param("limit", limit)
                    .query(this::mapCandidate).list();
        }
        return jdbc.sql("""
                SELECT post_id, published_at FROM feed_inbox
                 WHERE owner_id = :ownerId
                   AND (published_at < :publishedAt
                        OR (published_at = :publishedAt AND post_id < :postId))
                 ORDER BY published_at DESC, post_id DESC
                 LIMIT :limit
                """).param("ownerId", ownerId)
                .param("publishedAt", Timestamp.from(cursor.publishedAt()))
                .param("postId", cursor.postId()).param("limit", limit)
                .query(this::mapCandidate).list();
    }

    private FeedCandidate mapCandidate(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FeedCandidate(rs.getString("post_id"), rs.getTimestamp("published_at").toInstant());
    }
}
