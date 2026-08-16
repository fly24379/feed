package com.example.feed.repository;

import com.example.feed.domain.FanoutBackfillStatus;
import com.example.feed.domain.FanoutMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class FanoutBackfillJobRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, author_id, source_mode, target_mode, status, reason,
                   requested_limit, total_posts, processed_posts, inbox_rows_inserted,
                   last_published_at, last_post_id, failure_count, last_error,
                   available_at, processing_started_at, processor_id, created_by,
                   created_at, started_at, completed_at, updated_at
              FROM fanout_backfill_jobs
            """;

    private final JdbcClient jdbc;

    public FanoutBackfillJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public FanoutBackfillJob create(String id, long authorId, FanoutMode sourceMode,
                                    FanoutMode targetMode, String reason, Long requestedLimit,
                                    long totalPosts, Long createdBy) {
        FanoutBackfillStatus status = totalPosts == 0
                ? FanoutBackfillStatus.COMPLETED : FanoutBackfillStatus.PENDING;
        jdbc.sql("""
                INSERT INTO fanout_backfill_jobs(
                    id, author_id, source_mode, target_mode, status, reason,
                    requested_limit, total_posts, created_by, completed_at)
                VALUES (:id, :authorId, :sourceMode, :targetMode, :status, :reason,
                        :requestedLimit, :totalPosts, :createdBy,
                        IF(:status = 'COMPLETED', CURRENT_TIMESTAMP(6), NULL))
                """).param("id", id).param("authorId", authorId)
                .param("sourceMode", sourceMode.name()).param("targetMode", targetMode.name())
                .param("status", status.name()).param("reason", normalize(reason))
                .param("requestedLimit", requestedLimit).param("totalPosts", totalPosts)
                .param("createdBy", createdBy).update();
        return find(id).orElseThrow(() -> new IllegalStateException("回填任务写入后无法读取"));
    }

    public Optional<FanoutBackfillJob> find(String id) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE id = :id")
                .param("id", id).query(this::map).optional();
    }

    public Optional<FanoutBackfillJob> findForUpdate(String id) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE id = :id FOR UPDATE")
                .param("id", id).query(this::map).optional();
    }

    public List<FanoutBackfillJob> findRecent(Long authorId, FanoutBackfillStatus status, int limit) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append(" WHERE 1 = 1");
        if (authorId != null) {
            sql.append(" AND author_id = :authorId");
        }
        if (status != null) {
            sql.append(" AND status = :status");
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", limit);
        if (authorId != null) {
            statement = statement.param("authorId", authorId);
        }
        if (status != null) {
            statement = statement.param("status", status.name());
        }
        return statement.query(this::map).list();
    }

    public boolean hasActiveForAuthor(long authorId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM fanout_backfill_jobs
                 WHERE author_id = :authorId AND status IN ('PENDING', 'RUNNING', 'PAUSED')
                """).param("authorId", authorId).query(Integer.class).single() > 0;
    }

    public Optional<FanoutBackfillJob> claimNext(String processorId) {
        int updated = jdbc.sql("""
                UPDATE fanout_backfill_jobs
                   SET status = 'RUNNING', processor_id = :processorId,
                       processing_started_at = CURRENT_TIMESTAMP(6),
                       started_at = COALESCE(started_at, CURRENT_TIMESTAMP(6))
                 WHERE status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(6)
                 ORDER BY created_at, id
                 LIMIT 1
                """).param("processorId", processorId).update();
        if (updated == 0) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT_COLUMNS + """
                 WHERE status = 'RUNNING' AND processor_id = :processorId
                 ORDER BY processing_started_at DESC, id
                 LIMIT 1
                """).param("processorId", processorId).query(this::map).optional();
    }

    public void completeBatch(String id, String processorId, long processedDelta,
                              long inboxDelta, Instant lastPublishedAt, String lastPostId,
                              boolean completed) {
        int updated = jdbc.sql("""
                UPDATE fanout_backfill_jobs
                   SET processed_posts = processed_posts + :processedDelta,
                       inbox_rows_inserted = inbox_rows_inserted + :inboxDelta,
                       last_published_at = :lastPublishedAt,
                       last_post_id = :lastPostId,
                       status = :nextStatus,
                       processor_id = NULL,
                       processing_started_at = NULL,
                       available_at = CURRENT_TIMESTAMP(6),
                       completed_at = IF(:nextStatus = 'COMPLETED', CURRENT_TIMESTAMP(6), NULL)
                 WHERE id = :id AND status = 'RUNNING' AND processor_id = :processorId
                """).param("id", id).param("processorId", processorId)
                .param("processedDelta", processedDelta).param("inboxDelta", inboxDelta)
                .param("lastPublishedAt", lastPublishedAt == null ? null : Timestamp.from(lastPublishedAt))
                .param("lastPostId", lastPostId)
                .param("nextStatus", completed ? "COMPLETED" : "PENDING").update();
        if (updated != 1) {
            throw new IllegalStateException("回填任务批次状态已发生变化: " + id);
        }
    }

    public void completeWithoutMoreCandidates(String id, String processorId) {
        int updated = jdbc.sql("""
                UPDATE fanout_backfill_jobs
                   SET status = 'COMPLETED', total_posts = processed_posts,
                       processor_id = NULL, processing_started_at = NULL,
                       completed_at = CURRENT_TIMESTAMP(6)
                 WHERE id = :id AND status = 'RUNNING' AND processor_id = :processorId
                """).param("id", id).param("processorId", processorId).update();
        if (updated != 1) {
            throw new IllegalStateException("回填任务完成状态写入失败: " + id);
        }
    }

    public boolean markFailed(String id, String processorId, String error) {
        return jdbc.sql("""
                UPDATE fanout_backfill_jobs
                   SET status = 'FAILED', failure_count = failure_count + 1,
                       last_error = :error, processor_id = NULL, processing_started_at = NULL
                 WHERE id = :id AND status = 'RUNNING' AND processor_id = :processorId
                """).param("id", id).param("processorId", processorId)
                .param("error", truncate(error, 1000)).update() == 1;
    }

    public int recoverTimedOut(Instant deadline) {
        return jdbc.sql("""
                UPDATE fanout_backfill_jobs
                   SET status = 'PENDING', processor_id = NULL, processing_started_at = NULL,
                       available_at = CURRENT_TIMESTAMP(6),
                       last_error = 'worker lease timed out; task recovered'
                 WHERE status = 'RUNNING' AND processing_started_at < :deadline
                """).param("deadline", Timestamp.from(deadline)).update();
    }

    public boolean pause(String id) {
        return jdbc.sql("""
                UPDATE fanout_backfill_jobs
                   SET status = 'PAUSED', processor_id = NULL, processing_started_at = NULL
                 WHERE id = :id AND status IN ('PENDING', 'RUNNING')
                """).param("id", id).update() == 1;
    }

    public boolean resume(String id) {
        return jdbc.sql("""
                UPDATE fanout_backfill_jobs
                   SET status = 'PENDING', available_at = CURRENT_TIMESTAMP(6),
                       processor_id = NULL, processing_started_at = NULL
                 WHERE id = :id AND status = 'PAUSED'
                """).param("id", id).update() == 1;
    }

    public boolean retry(String id) {
        return jdbc.sql("""
                UPDATE fanout_backfill_jobs
                   SET status = 'PENDING', available_at = CURRENT_TIMESTAMP(6),
                       processor_id = NULL, processing_started_at = NULL, last_error = NULL
                 WHERE id = :id AND status = 'FAILED'
                """).param("id", id).update() == 1;
    }

    public boolean cancel(String id) {
        return jdbc.sql("""
                UPDATE fanout_backfill_jobs
                   SET status = 'CANCELLED', processor_id = NULL, processing_started_at = NULL,
                       completed_at = CURRENT_TIMESTAMP(6)
                 WHERE id = :id AND status IN ('PENDING', 'RUNNING', 'PAUSED', 'FAILED')
                """).param("id", id).update() == 1;
    }

    private FanoutBackfillJob map(ResultSet rs, int rowNum) throws SQLException {
        return new FanoutBackfillJob(
                rs.getString("id"), rs.getLong("author_id"),
                FanoutMode.valueOf(rs.getString("source_mode")),
                FanoutMode.valueOf(rs.getString("target_mode")),
                FanoutBackfillStatus.valueOf(rs.getString("status")), rs.getString("reason"),
                rs.getObject("requested_limit", Long.class), rs.getLong("total_posts"),
                rs.getLong("processed_posts"), rs.getLong("inbox_rows_inserted"),
                instant(rs, "last_published_at"), rs.getString("last_post_id"),
                rs.getInt("failure_count"), rs.getString("last_error"),
                instant(rs, "available_at"), instant(rs, "processing_started_at"),
                rs.getString("processor_id"), rs.getObject("created_by", Long.class),
                instant(rs, "created_at"), instant(rs, "started_at"),
                instant(rs, "completed_at"), instant(rs, "updated_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "unknown failure";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record FanoutBackfillJob(
            String id, long authorId, FanoutMode sourceMode, FanoutMode targetMode,
            FanoutBackfillStatus status, String reason, Long requestedLimit,
            long totalPosts, long processedPosts, long inboxRowsInserted,
            Instant lastPublishedAt, String lastPostId, int failureCount, String lastError,
            Instant availableAt, Instant processingStartedAt, String processorId,
            Long createdBy, Instant createdAt, Instant startedAt, Instant completedAt,
            Instant updatedAt) {

        public double progressPercent() {
            return totalPosts == 0 ? 100.0 : Math.min(100.0, processedPosts * 100.0 / totalPosts);
        }
    }
}
