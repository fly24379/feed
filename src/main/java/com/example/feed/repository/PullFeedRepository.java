package com.example.feed.repository;

import com.example.feed.domain.FeedCandidate;
import com.example.feed.domain.FeedCursor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class PullFeedRepository {
    private final JdbcClient jdbc;

    public PullFeedRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FeedCandidate> findPage(long viewerId, FeedCursor cursor, int limit) {
        String cursorPredicate = cursor == null ? "" : """
                   AND (p.published_at < :publishedAt
                        OR (p.published_at = :publishedAt AND p.id < :postId))
                """;
        var query = jdbc.sql("""
                SELECT p.id AS post_id, p.published_at
                  FROM friendships f
                  JOIN posts p
                    ON p.author_id = CASE
                       WHEN f.user_low = :viewerId THEN f.user_high ELSE f.user_low END
                 WHERE f.status = 'ACTIVE'
                   AND (f.user_low = :viewerId OR f.user_high = :viewerId)
                   AND p.delivery_mode = 'PULL'
                   AND p.status = 'ACTIVE'
                   AND p.author_id <> :viewerId
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = :viewerId AND b.blocked_id = p.author_id)
                           OR (b.blocked_id = :viewerId AND b.blocker_id = p.author_id)
                   )
                """ + cursorPredicate + """
                 ORDER BY p.published_at DESC, p.id DESC
                 LIMIT :limit
                """).param("viewerId", viewerId).param("limit", limit);
        if (cursor != null) {
            query.param("publishedAt", Timestamp.from(cursor.publishedAt()))
                    .param("postId", cursor.postId());
        }
        return query.query((rs, rowNum) -> new FeedCandidate(
                rs.getString("post_id"), rs.getTimestamp("published_at").toInstant())).list();
    }
}
