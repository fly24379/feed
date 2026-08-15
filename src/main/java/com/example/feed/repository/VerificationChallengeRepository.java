package com.example.feed.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VerificationChallengeRepository {
    private final JdbcClient jdbc;

    public VerificationChallengeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void create(UUID id, Long userId, String purpose, String channel, String target,
                       String codeHash, Instant expiresAt, String requestedAddress) {
        jdbc.sql("""
                INSERT INTO auth_verification_challenges
                       (id, user_id, purpose, channel, target, code_hash, expires_at, requested_address)
                VALUES (:id, :userId, :purpose, :channel, :target, :codeHash, :expiresAt, :address)
                """).param("id", id.toString()).param("userId", userId)
                .param("purpose", purpose).param("channel", channel).param("target", target)
                .param("codeHash", codeHash).param("expiresAt", Timestamp.from(expiresAt))
                .param("address", requestedAddress).update();
    }

    public boolean hasRecent(String purpose, String target, Instant since) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM auth_verification_challenges
                 WHERE purpose = :purpose AND target = :target AND created_at >= :since
                """).param("purpose", purpose).param("target", target)
                .param("since", Timestamp.from(since)).query(Integer.class).single() > 0;
    }

    public Optional<Challenge> findForUpdate(UUID id) {
        return jdbc.sql("""
                SELECT id, user_id, purpose, channel, target, code_hash, expires_at,
                       consumed_at, attempts
                  FROM auth_verification_challenges
                 WHERE id = :id
                 FOR UPDATE
                """).param("id", id.toString()).query((rs, rowNum) -> new Challenge(
                        UUID.fromString(rs.getString("id")),
                        rs.getObject("user_id", Long.class),
                        rs.getString("purpose"), rs.getString("channel"), rs.getString("target"),
                        rs.getString("code_hash"), rs.getTimestamp("expires_at").toInstant(),
                        toInstant(rs.getTimestamp("consumed_at")), rs.getInt("attempts")))
                .optional();
    }

    public void recordFailure(UUID id) {
        jdbc.sql("""
                UPDATE auth_verification_challenges SET attempts = attempts + 1
                 WHERE id = :id AND consumed_at IS NULL
                """).param("id", id.toString()).update();
    }

    public boolean consume(UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE auth_verification_challenges SET consumed_at = :now
                 WHERE id = :id AND consumed_at IS NULL
                """).param("id", id.toString()).param("now", Timestamp.from(now)).update() == 1;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record Challenge(UUID id, Long userId, String purpose, String channel, String target,
                            String codeHash, Instant expiresAt, Instant consumedAt, int attempts) {
    }
}
