package com.example.feed.security;

import com.example.feed.repository.UserRepository.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {
    private static final String SECRET = "test-secret-with-at-least-thirty-two-bytes";

    @Test
    void issuesTokenThatDecoderValidates() {
        SecurityConfig config = new SecurityConfig(SECRET, "https://friend-feed.test");
        JwtTokenService tokens = new JwtTokenService(
                config.jwtEncoder(), Clock.systemUTC(), "https://friend-feed.test", Duration.ofHours(2));
        JwtDecoder decoder = config.jwtDecoder();

        JwtTokenService.AccessToken accessToken = tokens.issue(
                new AuthUser(42, "alice", "Alice", "not-exposed"));
        Jwt decoded = decoder.decode(accessToken.accessToken());

        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(decoded.getIssuer().toString()).isEqualTo("https://friend-feed.test");
        assertThat(decoded.getClaimAsString("username")).isEqualTo("alice");
        assertThat(accessToken.expiresIn()).isEqualTo(7200);
    }

    @Test
    void rejectsSecretThatIsTooShortForHs256() {
        assertThatThrownBy(() -> new SecurityConfig("too-short", "issuer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32");
    }
}
