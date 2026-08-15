package com.example.feed.repository;

import com.example.feed.domain.FanoutMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class FanoutPolicyRepository {
    private final JdbcClient jdbc;

    public FanoutPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public FanoutMode resolveMode(long authorId) {
        return jdbc.sql("SELECT fanout_mode FROM feed_author_policy WHERE author_id = :authorId")
                .param("authorId", authorId)
                .query(String.class).optional()
                .map(FanoutMode::valueOf)
                .orElse(FanoutMode.PUSH);
    }

    public Optional<FanoutPolicy> find(long authorId) {
        return jdbc.sql("""
                SELECT author_id, fanout_mode, reason, updated_at
                  FROM feed_author_policy
                 WHERE author_id = :authorId
                """).param("authorId", authorId)
                .query((rs, rowNum) -> new FanoutPolicy(
                        rs.getLong("author_id"), FanoutMode.valueOf(rs.getString("fanout_mode")),
                        rs.getString("reason"), rs.getTimestamp("updated_at").toInstant(), true))
                .optional();
    }

    public void upsert(long authorId, FanoutMode mode, String reason) {
        jdbc.sql("""
                INSERT INTO feed_author_policy(author_id, fanout_mode, reason)
                VALUES (:authorId, :mode, :reason)
                ON DUPLICATE KEY UPDATE fanout_mode = VALUES(fanout_mode), reason = VALUES(reason)
                """).param("authorId", authorId).param("mode", mode.name())
                .param("reason", normalizeReason(reason)).update();
    }

    public void delete(long authorId) {
        jdbc.sql("DELETE FROM feed_author_policy WHERE author_id = :authorId")
                .param("authorId", authorId).update();
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.strip();
    }

    public record FanoutPolicy(long authorId, FanoutMode mode, String reason,
                               Instant updatedAt, boolean explicit) {
        public static FanoutPolicy defaultPush(long authorId) {
            return new FanoutPolicy(authorId, FanoutMode.PUSH, null, null, false);
        }
    }
}
