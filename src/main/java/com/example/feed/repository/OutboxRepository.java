package com.example.feed.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    public List<OutboxEvent> findDuePending(int limit) {
        return jdbc.sql("""
                SELECT id, aggregate_id, attempts, created_at
                  FROM outbox_events
                 WHERE status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(6)
                 ORDER BY id
                 LIMIT :limit
                """).param("limit", limit).query(this::mapEvent).list();
    }

    public boolean markDispatching(long eventId, String processorId) {
        return jdbc.sql("""
                UPDATE outbox_events
                   SET status = 'PROCESSING', attempts = attempts + 1,
                       processing_started_at = CURRENT_TIMESTAMP(6), processor_id = :processorId,
                       last_error = NULL
                 WHERE id = :id AND status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(6)
                """).param("id", eventId).param("processorId", processorId).update() == 1;
    }

    public void markDispatched(long eventId, String processorId) {
        jdbc.sql("""
                UPDATE outbox_events
                   SET status = 'DISPATCHED'
                 WHERE id = :id AND status = 'PROCESSING' AND processor_id = :processorId
                """).param("id", eventId).param("processorId", processorId).update();
    }

    public boolean claimForConsumption(long eventId, String consumerId) {
        return jdbc.sql("""
                UPDATE outbox_events
                   SET status = 'PROCESSING', processing_started_at = CURRENT_TIMESTAMP(6),
                       processor_id = :consumerId
                 WHERE id = :id
                   AND (status = 'DISPATCHED'
                        OR (status = 'PROCESSING' AND processor_id LIKE 'dispatcher:%'))
                """).param("id", eventId).param("consumerId", consumerId).update() == 1;
    }

    public Optional<OutboxEvent> findById(long eventId) {
        return jdbc.sql("""
                SELECT id, aggregate_id, attempts, created_at
                  FROM outbox_events WHERE id = :id
                """).param("id", eventId).query(this::mapEvent).optional();
    }

    public Optional<String> findStatus(long eventId) {
        return jdbc.sql("SELECT status FROM outbox_events WHERE id = :id")
                .param("id", eventId).query(String.class).optional();
    }

    public void markProcessed(long eventId, String consumerId) {
        jdbc.sql("""
                UPDATE outbox_events
                   SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP(6),
                       processing_started_at = NULL, processor_id = NULL, last_error = NULL
                 WHERE id = :id AND status = 'PROCESSING' AND processor_id = :consumerId
                """).param("id", eventId).param("consumerId", consumerId).update();
    }

    public boolean scheduleRetry(long eventId, String processorId, Instant availableAt, String error,
                                 int maxAttempts) {
        return jdbc.sql("""
                UPDATE outbox_events
                   SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                       available_at = :availableAt, processing_started_at = NULL, processor_id = NULL,
                       last_error = :error
                 WHERE id = :id AND status = 'PROCESSING' AND processor_id = :processorId
                """).param("id", eventId).param("processorId", processorId)
                .param("availableAt", Timestamp.from(availableAt)).param("error", truncate(error))
                .param("maxAttempts", maxAttempts).update() == 1;
    }

    public List<TimedOutEvent> findTimedOut(Instant before, int limit) {
        return jdbc.sql("""
                SELECT id, aggregate_id, attempts, processor_id, processing_started_at, created_at
                  FROM outbox_events
                 WHERE status IN ('PROCESSING','DISPATCHED') AND processing_started_at < :before
                 ORDER BY processing_started_at
                 LIMIT :limit
                """).param("before", Timestamp.from(before))
                .param("limit", limit)
                .query((rs, rowNum) -> new TimedOutEvent(rs.getLong("id"), rs.getString("aggregate_id"),
                        rs.getInt("attempts"), rs.getString("processor_id"),
                        rs.getTimestamp("processing_started_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant())).list();
    }

    public boolean recoverTimedOut(TimedOutEvent event, Instant availableAt, int maxAttempts) {
        return jdbc.sql("""
                UPDATE outbox_events
                   SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                       available_at = :availableAt, processing_started_at = NULL, processor_id = NULL,
                       last_error = 'processing timeout recovered'
                 WHERE id = :id AND status IN ('PROCESSING','DISPATCHED') AND processor_id = :processorId
                   AND processing_started_at = :processingStartedAt
                """).param("id", event.id()).param("processorId", event.processorId())
                .param("processingStartedAt", Timestamp.from(event.processingStartedAt()))
                .param("availableAt", Timestamp.from(availableAt)).param("maxAttempts", maxAttempts)
                .update() == 1;
    }

    public boolean replayFailed(long eventId) {
        return jdbc.sql("""
                UPDATE outbox_events
                   SET status = 'PENDING', attempts = 0, available_at = CURRENT_TIMESTAMP(6),
                       processing_started_at = NULL, processor_id = NULL, processed_at = NULL,
                       last_error = NULL, replay_count = replay_count + 1,
                       replayed_at = CURRENT_TIMESTAMP(6)
                 WHERE id = :id AND status = 'FAILED'
                """).param("id", eventId).update() == 1;
    }

    public long countBacklog() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE status IN ('PENDING','PROCESSING','DISPATCHED')")
                .query(Long.class).single();
    }

    public long countFailed() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE status = 'FAILED'")
                .query(Long.class).single();
    }

    public double oldestBacklogAgeSeconds() {
        Long micros = jdbc.sql("""
                SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(6)), 0)
                  FROM outbox_events WHERE status IN ('PENDING','PROCESSING','DISPATCHED')
                """).query(Long.class).single();
        return micros / 1_000_000.0;
    }

    public double recentAverageProcessingLatencySeconds() {
        Double micros = jdbc.sql("""
                SELECT COALESCE(AVG(TIMESTAMPDIFF(MICROSECOND, created_at, processed_at)), 0)
                  FROM outbox_events
                 WHERE status = 'PROCESSED' AND processed_at >= CURRENT_TIMESTAMP(6) - INTERVAL 5 MINUTE
                """).query(Double.class).single();
        return micros / 1_000_000.0;
    }

    private OutboxEvent mapEvent(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OutboxEvent(rs.getLong("id"), rs.getString("aggregate_id"), rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant());
    }

    private String truncate(String error) {
        if (error == null || error.isBlank()) {
            return "unknown error";
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    public record OutboxEvent(long id, String postId, int attempts, Instant createdAt) {
    }

    public record TimedOutEvent(long id, String postId, int attempts, String processorId,
                                Instant processingStartedAt, Instant createdAt) {
    }
}
