package com.example.feed.security;

import com.example.feed.api.LoginRateLimitException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    @Test
    void fallsBackToLocalLimitWhenRedisIsUnavailable() {
        LoginRateLimiter limiter = new LoginRateLimiter(null,
                Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC),
                2, 10, Duration.ofMinutes(15), Duration.ofMinutes(5));

        limiter.recordFailure("alice", "127.0.0.1");
        limiter.recordFailure("alice", "127.0.0.1");

        assertThatThrownBy(() -> limiter.checkAllowed("alice", "127.0.0.1"))
                .isInstanceOf(LoginRateLimitException.class)
                .satisfies(exception -> {
                    LoginRateLimitException limited = (LoginRateLimitException) exception;
                    org.assertj.core.api.Assertions.assertThat(limited.retryAfterSeconds()).isEqualTo(300);
                });
    }
}
