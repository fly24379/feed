package com.example.feed.repository;

import com.example.feed.repository.UserRepository.AuthUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AuthSessionRepository {
    private final JdbcClient jdbc;

    public AuthSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void create(UUID sessionId, long userId, UUID tokenId, String tokenHash, Instant expiresAt,
                       String clientAddress, String userAgent) {
        jdbc.sql("""
                INSERT INTO auth_sessions(id, user_id, expires_at, client_address, user_agent)
                VALUES (:id, :userId, :expiresAt, :clientAddress, :userAgent)
                """).param("id", sessionId.toString()).param("userId", userId)
                .param("expiresAt", Timestamp.from(expiresAt)).param("clientAddress", clientAddress)
                .param("userAgent", userAgent).update();
        insertRefreshToken(tokenId, sessionId, tokenHash, expiresAt);
    }

    public Optional<RefreshTokenRecord> findRefreshTokenForUpdate(String tokenHash) {
        return jdbc.sql("""
                SELECT rt.id AS token_id, rt.session_id, rt.expires_at AS token_expires_at,
                       rt.used_at, rt.revoked_at AS token_revoked_at,
                       s.expires_at AS session_expires_at, s.revoked_at AS session_revoked_at,
                       u.id AS user_id, u.username, u.nickname, u.password_hash, u.role
                  FROM auth_refresh_tokens rt
                  JOIN auth_sessions s ON s.id = rt.session_id
                  JOIN users u ON u.id = s.user_id
                 WHERE rt.token_hash = :tokenHash
                 FOR UPDATE
                """).param("tokenHash", tokenHash).query((rs, rowNum) -> new RefreshTokenRecord(
                        UUID.fromString(rs.getString("token_id")),
                        UUID.fromString(rs.getString("session_id")),
                        new AuthUser(rs.getLong("user_id"), rs.getString("username"),
                                rs.getString("nickname"), rs.getString("password_hash"), rs.getString("role")),
                        rs.getTimestamp("token_expires_at").toInstant(),
                        toInstant(rs.getTimestamp("used_at")),
                        toInstant(rs.getTimestamp("token_revoked_at")),
                        rs.getTimestamp("session_expires_at").toInstant(),
                        toInstant(rs.getTimestamp("session_revoked_at"))))
                .optional();
    }

    public boolean rotate(UUID oldTokenId, UUID newTokenId, UUID sessionId, String newTokenHash,
                          Instant expiresAt, Instant now) {
        int updated = jdbc.sql("""
                UPDATE auth_refresh_tokens
                   SET used_at = :now, replaced_by_id = :replacement
                 WHERE id = :id AND used_at IS NULL AND revoked_at IS NULL
                """).param("now", Timestamp.from(now)).param("replacement", newTokenId.toString())
                .param("id", oldTokenId.toString()).update();
        if (updated != 1) {
            return false;
        }
        insertRefreshToken(newTokenId, sessionId, newTokenHash, expiresAt);
        jdbc.sql("UPDATE auth_sessions SET last_used_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now)).param("id", sessionId.toString()).update();
        return true;
    }

    public void revoke(UUID sessionId, Instant now) {
        jdbc.sql("""
                UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, :now)
                 WHERE id = :id
                """).param("now", Timestamp.from(now)).param("id", sessionId.toString()).update();
        jdbc.sql("""
                UPDATE auth_refresh_tokens SET revoked_at = COALESCE(revoked_at, :now)
                 WHERE session_id = :id
                """).param("now", Timestamp.from(now)).param("id", sessionId.toString()).update();
    }

    public void revoke(UUID sessionId, long userId, Instant now) {
        int updated = jdbc.sql("""
                UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, :now)
                 WHERE id = :id AND user_id = :userId
                """).param("now", Timestamp.from(now)).param("id", sessionId.toString())
                .param("userId", userId).update();
        if (updated == 1) {
            jdbc.sql("""
                    UPDATE auth_refresh_tokens SET revoked_at = COALESCE(revoked_at, :now)
                     WHERE session_id = :id
                    """).param("now", Timestamp.from(now)).param("id", sessionId.toString()).update();
        }
    }

    public boolean isActive(String sessionId) {
        try {
            UUID id = UUID.fromString(sessionId);
            return jdbc.sql("""
                    SELECT COUNT(*) FROM auth_sessions
                     WHERE id = :id AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP(6)
                    """).param("id", id.toString()).query(Integer.class).single() == 1;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public int deleteExpiredOrRevokedBefore(Instant before) {
        return jdbc.sql("""
                DELETE FROM auth_sessions
                 WHERE expires_at < :before OR (revoked_at IS NOT NULL AND revoked_at < :before)
                """).param("before", Timestamp.from(before)).update();
    }

    private void insertRefreshToken(UUID tokenId, UUID sessionId, String tokenHash, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO auth_refresh_tokens(id, session_id, token_hash, expires_at)
                VALUES (:id, :sessionId, :tokenHash, :expiresAt)
                """).param("id", tokenId.toString()).param("sessionId", sessionId.toString())
                .param("tokenHash", tokenHash).param("expiresAt", Timestamp.from(expiresAt)).update();
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record RefreshTokenRecord(UUID tokenId, UUID sessionId, AuthUser user,
                                     Instant tokenExpiresAt, Instant usedAt, Instant tokenRevokedAt,
                                     Instant sessionExpiresAt, Instant sessionRevokedAt) {
    }
}
