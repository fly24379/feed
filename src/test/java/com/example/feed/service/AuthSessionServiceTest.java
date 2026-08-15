package com.example.feed.service;

import com.example.feed.api.InvalidRefreshTokenException;
import com.example.feed.repository.AuthSessionRepository;
import com.example.feed.repository.AuthSessionRepository.RefreshTokenRecord;
import com.example.feed.repository.UserRepository;
import com.example.feed.repository.UserRepository.AuthUser;
import com.example.feed.security.JwtTokenService;
import com.example.feed.security.JwtTokenService.AccessToken;
import com.example.feed.security.LoginRateLimiter;
import com.example.feed.security.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final JwtTokenService jwt = mock(JwtTokenService.class);
    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
    private final AuthService service = new AuthService(users, passwords, jwt, sessions, refreshTokens,
            rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30));
    private final AuthUser alice = new AuthUser(7, "alice", "Alice", "$2-hash", "USER");

    @Test
    void loginCreatesServerSessionAndReturnsRefreshToken() {
        AccessToken access = new AccessToken("jwt", "Bearer", 900, 7, "alice", "Alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(passwords.matches("very-secret", "$2-hash")).thenReturn(true);
        when(refreshTokens.generate()).thenReturn("raw-refresh");
        when(refreshTokens.hash("raw-refresh")).thenReturn("stored-hash");
        when(jwt.issue(eq(alice), any(UUID.class))).thenReturn(access);

        AuthService.AuthTokens result = service.login(
                " ALICE ", "very-secret", "127.0.0.1", "test-agent");

        assertThat(result.accessToken()).isEqualTo("jwt");
        assertThat(result.refreshToken()).isEqualTo("raw-refresh");
        assertThat(result.refreshExpiresIn()).isEqualTo(Duration.ofDays(30).toSeconds());
        verify(rateLimiter).checkAllowed("alice", "127.0.0.1");
        verify(rateLimiter).recordSuccess("alice");
        verify(sessions).create(any(UUID.class), eq(7L), any(UUID.class), eq("stored-hash"),
                eq(NOW.plus(Duration.ofDays(30))), eq("127.0.0.1"), eq("test-agent"));
    }

    @Test
    void refreshConsumesCurrentTokenAndRotatesIt() {
        UUID tokenId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = NOW.plus(Duration.ofDays(30));
        RefreshTokenRecord current = new RefreshTokenRecord(tokenId, sessionId, alice,
                expiresAt, null, null, expiresAt, null);
        AccessToken access = new AccessToken("new-jwt", "Bearer", 900, 7, "alice", "Alice");
        when(refreshTokens.hash("old-refresh")).thenReturn("old-hash");
        when(sessions.findRefreshTokenForUpdate("old-hash")).thenReturn(Optional.of(current));
        when(refreshTokens.generate()).thenReturn("new-refresh");
        when(refreshTokens.hash("new-refresh")).thenReturn("new-hash");
        when(sessions.rotate(eq(tokenId), any(UUID.class), eq(sessionId), eq("new-hash"),
                eq(expiresAt), eq(NOW))).thenReturn(true);
        when(jwt.issue(alice, sessionId)).thenReturn(access);

        AuthService.AuthTokens result = service.refresh("old-refresh");

        assertThat(result.accessToken()).isEqualTo("new-jwt");
        assertThat(result.refreshToken()).isEqualTo("new-refresh");
        verify(sessions).rotate(eq(tokenId), any(UUID.class), eq(sessionId), eq("new-hash"),
                eq(expiresAt), eq(NOW));
    }

    @Test
    void replayedRefreshTokenRevokesWholeSession() {
        UUID sessionId = UUID.randomUUID();
        RefreshTokenRecord replayed = new RefreshTokenRecord(UUID.randomUUID(), sessionId, alice,
                NOW.plusSeconds(60), NOW.minusSeconds(1), null, NOW.plusSeconds(60), null);
        when(refreshTokens.hash("replayed")).thenReturn("hash");
        when(sessions.findRefreshTokenForUpdate("hash")).thenReturn(Optional.of(replayed));

        assertThatThrownBy(() -> service.refresh("replayed"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(sessions).revoke(sessionId, NOW);
    }
}
