package com.example.feed.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class OutboxRepository {
    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void addPostPublished(String postId) {
        jdbc.sql("""
                INSERT INTO outbox_events(aggregate_id, event_type, status)
                VALUES (:postId, 'POST_PUBLISHED', 'PENDING')
                """).param("postId", postId).update();
    }

    public Optional<OutboxEvent> lockNextPending() {
        return jdbc.sql("""
                SELECT id, aggregate_id FROM outbox_events
                 WHERE status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(6)
                 ORDER BY id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """).query((rs, rowNum) -> new OutboxEvent(rs.getLong("id"), rs.getString("aggregate_id")))
                .optional();
    }

    public void markProcessed(long eventId) {
        jdbc.sql("""
                UPDATE outbox_events
                   SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP(6)
                 WHERE id = :id
                """).param("id", eventId).update();
    }

    public record OutboxEvent(long id, String postId) {
    }
}
