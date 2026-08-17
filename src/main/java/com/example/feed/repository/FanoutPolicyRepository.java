package com.example.feed.repository;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicySource;
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
                SELECT author_id, fanout_mode, policy_source, reason,
                       evaluated_friend_count, evaluated_at, updated_at
                  FROM feed_author_policy
                 WHERE author_id = :authorId
                """).param("authorId", authorId)
                .query((rs, rowNum) -> new FanoutPolicy(
                        rs.getLong("author_id"), FanoutMode.valueOf(rs.getString("fanout_mode")),
                        FanoutPolicySource.valueOf(rs.getString("policy_source")), rs.getString("reason"),
                        rs.getObject("evaluated_friend_count", Long.class),
                        rs.getTimestamp("evaluated_at") == null ? null : rs.getTimestamp("evaluated_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), true))
                .optional();
    }

    public void upsert(long authorId, FanoutMode mode, String reason) {
        jdbc.sql("""
                INSERT INTO feed_author_policy(author_id, fanout_mode, policy_source, reason,
                                               evaluated_friend_count, evaluated_at)
                VALUES (:authorId, :mode, 'MANUAL', :reason, NULL, NULL)
                ON DUPLICATE KEY UPDATE fanout_mode = VALUES(fanout_mode),
                    policy_source = 'MANUAL', reason = VALUES(reason),
                    evaluated_friend_count = NULL, evaluated_at = NULL
                """).param("authorId", authorId).param("mode", mode.name())
                .param("reason", normalizeReason(reason)).update();
    }

    public void upsertAuto(long authorId, FanoutMode mode, long followerCount) {
        jdbc.sql("""
                INSERT INTO feed_author_policy(author_id, fanout_mode, policy_source, reason,
                                               evaluated_friend_count, evaluated_at)
                VALUES (:authorId, :mode, 'AUTO', 'automatic follower threshold',
                        :followerCount, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    fanout_mode = IF(policy_source = 'AUTO', VALUES(fanout_mode), fanout_mode),
                    reason = IF(policy_source = 'AUTO', VALUES(reason), reason),
                    evaluated_friend_count = IF(policy_source = 'AUTO', VALUES(evaluated_friend_count), evaluated_friend_count),
                    evaluated_at = IF(policy_source = 'AUTO', VALUES(evaluated_at), evaluated_at)
                """).param("authorId", authorId).param("mode", mode.name())
                .param("followerCount", followerCount).update();
    }

    public void deleteAuto(long authorId) {
        jdbc.sql("DELETE FROM feed_author_policy WHERE author_id = :authorId AND policy_source = 'AUTO'")
                .param("authorId", authorId).update();
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

    public record FanoutPolicy(long authorId, FanoutMode mode, FanoutPolicySource source, String reason,
                               Long evaluatedFollowerCount, Instant evaluatedAt,
                               Instant updatedAt, boolean explicit) {
        public static FanoutPolicy defaultPush(long authorId) {
            return new FanoutPolicy(authorId, FanoutMode.PUSH, null, null,
                    null, null, null, false);
        }
    }
}
