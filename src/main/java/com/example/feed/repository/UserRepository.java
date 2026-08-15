package com.example.feed.repository;

import com.example.feed.api.NotFoundException;
import com.example.feed.domain.UserProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {
    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String username, String nickname, String passwordHash) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO users(username, nickname, password_hash)
                VALUES (:username, :nickname, :passwordHash)
                """).param("username", username).param("nickname", nickname)
                .param("passwordHash", passwordHash).update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建用户后未返回主键");
        }
        return key.longValue();
    }

    public long createVerified(String username, String nickname, String passwordHash,
                               String channel, String target, Instant verifiedAt) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        String email = "EMAIL".equals(channel) ? target : null;
        String phone = "PHONE".equals(channel) ? target : null;
        jdbc.sql("""
                INSERT INTO users(username, nickname, password_hash, email, phone,
                                  email_verified_at, phone_verified_at)
                VALUES (:username, :nickname, :passwordHash, :email, :phone,
                        :emailVerifiedAt, :phoneVerifiedAt)
                """).param("username", username).param("nickname", nickname)
                .param("passwordHash", passwordHash).param("email", email).param("phone", phone)
                .param("emailVerifiedAt", email == null ? null : Timestamp.from(verifiedAt))
                .param("phoneVerifiedAt", phone == null ? null : Timestamp.from(verifiedAt))
                .update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建用户后未返回主键");
        }
        return key.longValue();
    }

    public boolean existsByVerifiedContact(String channel, String target) {
        String column = "EMAIL".equals(channel) ? "email" : "phone";
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE " + column + " = :target")
                .param("target", target).query(Integer.class).single() > 0;
    }

    public Optional<RecoveryAccount> findRecoveryAccount(String account) {
        return jdbc.sql("""
                SELECT id, email, phone, email_verified_at, phone_verified_at
                  FROM users
                 WHERE username = :account OR email = :account OR phone = :account
                 LIMIT 1
                """).param("account", account).query((rs, rowNum) -> new RecoveryAccount(
                        rs.getLong("id"), rs.getString("email"), rs.getString("phone"),
                        rs.getTimestamp("email_verified_at") != null,
                        rs.getTimestamp("phone_verified_at") != null)).optional();
    }

    public void updatePasswordAndRevokeSessions(long userId, String passwordHash, Instant changedAt) {
        Timestamp now = Timestamp.from(changedAt);
        jdbc.sql("""
                UPDATE users SET password_hash = :passwordHash, password_changed_at = :changedAt
                 WHERE id = :id
                """).param("passwordHash", passwordHash).param("changedAt", now)
                .param("id", userId).update();
        jdbc.sql("""
                UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, :now)
                 WHERE user_id = :userId
                """).param("now", now).param("userId", userId).update();
        jdbc.sql("""
                UPDATE auth_refresh_tokens rt
                  JOIN auth_sessions s ON s.id = rt.session_id
                   SET rt.revoked_at = COALESCE(rt.revoked_at, :now)
                 WHERE s.user_id = :userId
                """).param("now", now).param("userId", userId).update();
    }

    public boolean existsByUsername(String username) {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE username = :username")
                .param("username", username).query(Integer.class).single() > 0;
    }

    public Optional<AuthUser> findByUsername(String username) {
        return jdbc.sql("""
                SELECT id, username, nickname, password_hash, role
                  FROM users WHERE username = :username
                """).param("username", username)
                .query((rs, rowNum) -> new AuthUser(rs.getLong("id"), rs.getString("username"),
                        rs.getString("nickname"), rs.getString("password_hash"), rs.getString("role")))
                .optional();
    }

    public boolean exists(long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE id = :id")
                .param("id", userId)
                .query(Integer.class)
                .single() > 0;
    }

    public void requireExists(long userId) {
        if (!exists(userId)) {
            throw new NotFoundException("用户不存在: " + userId);
        }
    }

    public UserProfile requireProfile(long userId) {
        return findProfile(userId).orElseThrow(() -> new NotFoundException("用户不存在: " + userId));
    }

    public Optional<UserProfile> findProfile(long userId) {
        return jdbc.sql("""
                SELECT id, username, nickname, bio, avatar_url
                  FROM users WHERE id = :id
                """).param("id", userId).query(this::mapProfile).optional();
    }

    public List<UserProfile> search(String keyword, long afterId, int limit) {
        return jdbc.sql("""
                SELECT id, username, nickname, bio, avatar_url
                  FROM users
                 WHERE id > :afterId
                   AND (username LIKE CONCAT('%', :keyword, '%')
                        OR nickname LIKE CONCAT('%', :keyword, '%'))
                 ORDER BY id
                 LIMIT :limit
                """).param("afterId", afterId).param("keyword", keyword).param("limit", limit)
                .query(this::mapProfile).list();
    }

    public void updateProfile(long userId, String nickname, String bio, String avatarUrl) {
        jdbc.sql("""
                UPDATE users SET nickname = :nickname, bio = :bio, avatar_url = :avatarUrl
                 WHERE id = :id
                """).param("id", userId).param("nickname", nickname)
                .param("bio", bio).param("avatarUrl", avatarUrl).update();
    }

    private UserProfile mapProfile(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserProfile(rs.getLong("id"), rs.getString("username"), rs.getString("nickname"),
                rs.getString("bio"), rs.getString("avatar_url"));
    }

    public record AuthUser(long id, String username, String nickname, String passwordHash, String role) {
    }

    public record RecoveryAccount(long id, String email, String phone,
                                  boolean emailVerified, boolean phoneVerified) {
    }
}
