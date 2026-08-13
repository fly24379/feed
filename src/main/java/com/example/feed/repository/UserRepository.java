package com.example.feed.repository;

import com.example.feed.api.NotFoundException;
import com.example.feed.domain.UserProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

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
}
