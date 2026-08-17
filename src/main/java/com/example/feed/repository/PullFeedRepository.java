package com.example.feed.repository;

import com.example.feed.domain.FeedCandidate;
import com.example.feed.domain.FeedCursor;
import com.example.feed.service.AuthorTimelineCache;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class PullFeedRepository {
    private final JdbcClient jdbc;
    private final AuthorTimelineCache cache;

    public PullFeedRepository(JdbcClient jdbc, AuthorTimelineCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    public List<FeedCandidate> findPage(long viewerId, FeedCursor cursor, int limit) {
        Map<String, FeedCandidate> unique = new LinkedHashMap<>();
        findEligibleAuthorIds(viewerId).stream()
                .flatMap(authorId -> cache.findPage(authorId, cursor, limit,
                        (authorCursor, authorLimit) -> findAuthorPageFromDatabase(
                                authorId, authorCursor, authorLimit)).stream())
                .sorted(Comparator.comparing(FeedCandidate::publishedAt)
                        .thenComparing(FeedCandidate::postId).reversed())
                .forEach(candidate -> unique.putIfAbsent(candidate.postId(), candidate));
        return unique.values().stream().limit(limit).toList();
    }

    public List<FeedCandidate> findPageFromDatabase(long viewerId, FeedCursor cursor, int limit) {
        String cursorPredicate = cursor == null ? "" : """
                   AND (p.published_at < :publishedAt
                        OR (p.published_at = :publishedAt AND p.id < :postId))
                """;
        var query = jdbc.sql("""
                SELECT p.id AS post_id, p.published_at
                  FROM follows f
                  JOIN posts p ON p.author_id = f.followee_id
                 WHERE f.follower_id = :viewerId
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

    private List<Long> findEligibleAuthorIds(long viewerId) {
        return jdbc.sql("""
                SELECT f.followee_id AS author_id
                  FROM follows f
                 WHERE f.follower_id = :viewerId
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = :viewerId AND b.blocked_id = f.followee_id)
                           OR (b.blocked_id = :viewerId AND b.blocker_id = f.followee_id)
                   )
                   AND EXISTS (
                       SELECT 1 FROM posts p
                        WHERE p.author_id = f.followee_id
                          AND p.delivery_mode = 'PULL' AND p.status = 'ACTIVE'
                   )
                """).param("viewerId", viewerId).query(Long.class).list();
    }

    private List<FeedCandidate> findAuthorPageFromDatabase(long authorId, FeedCursor cursor, int limit) {
        String cursorPredicate = cursor == null ? "" : """
                   AND (published_at < :publishedAt
                        OR (published_at = :publishedAt AND id < :postId))
                """;
        var query = jdbc.sql("""
                SELECT id AS post_id, published_at FROM posts
                 WHERE author_id = :authorId AND delivery_mode = 'PULL' AND status = 'ACTIVE'
                """ + cursorPredicate + """
                 ORDER BY published_at DESC, id DESC LIMIT :limit
                """).param("authorId", authorId).param("limit", limit);
        if (cursor != null) {
            query.param("publishedAt", Timestamp.from(cursor.publishedAt())).param("postId", cursor.postId());
        }
        return query.query((rs, rowNum) -> new FeedCandidate(
                rs.getString("post_id"), rs.getTimestamp("published_at").toInstant())).list();
    }
}
