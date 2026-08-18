package com.example.feed.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Moves aged materialized-feed candidates into a monthly audit/archive table.
 * Post facts remain in {@code posts}; this table records only the historical delivery candidate.
 */
@Repository
public class FeedInboxArchiveRepository {
    private final JdbcClient jdbc;

    public FeedInboxArchiveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Must be called inside a transaction. SKIP LOCKED lets multiple application instances
     * archive disjoint batches without blocking each other or the homepage's normal reads.
     */
    public int archiveNextBatch(Instant cutoff, int limit) {
        List<InboxEntry> candidates = jdbc.sql("""
                SELECT owner_id, post_id, author_id, published_at
                  FROM feed_inbox
                 WHERE published_at < :cutoff
                 ORDER BY published_at, post_id, owner_id
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
                """).param("cutoff", Timestamp.from(cutoff)).param("limit", limit)
                .query((rs, rowNum) -> new InboxEntry(
                        rs.getLong("owner_id"), rs.getString("post_id"), rs.getLong("author_id"),
                        rs.getTimestamp("published_at").toInstant()))
                .list();
        for (InboxEntry entry : candidates) {
            jdbc.sql("""
                    INSERT IGNORE INTO feed_inbox_archive(
                        archive_month, owner_id, post_id, author_id, published_at)
                    VALUES (:archiveMonth, :ownerId, :postId, :authorId, :publishedAt)
                    """).param("archiveMonth", Date.valueOf(entry.publishedAt().atZone(ZoneOffset.UTC)
                            .withDayOfMonth(1).toLocalDate()))
                    .param("ownerId", entry.ownerId()).param("postId", entry.postId())
                    .param("authorId", entry.authorId())
                    .param("publishedAt", Timestamp.from(entry.publishedAt())).update();
            jdbc.sql("""
                    DELETE FROM feed_inbox
                     WHERE owner_id = :ownerId AND post_id = :postId
                    """).param("ownerId", entry.ownerId()).param("postId", entry.postId()).update();
        }
        return candidates.size();
    }

    private record InboxEntry(long ownerId, String postId, long authorId, Instant publishedAt) {
    }
}
