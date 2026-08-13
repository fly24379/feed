package com.example.feed.repository;

import com.example.feed.api.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

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
                SELECT id, username, nickname, password_hash
                  FROM users WHERE username = :username
                """).param("username", username)
                .query((rs, rowNum) -> new AuthUser(rs.getLong("id"), rs.getString("username"),
                        rs.getString("nickname"), rs.getString("password_hash")))
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

    public record AuthUser(long id, String username, String nickname, String passwordHash) {
    }
}
