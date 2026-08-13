package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.repository.UserRepository;
import com.example.feed.repository.UserRepository.AuthUser;
import com.example.feed.security.JwtTokenService;
import com.example.feed.security.JwtTokenService.AccessToken;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final JwtTokenService tokens = mock(JwtTokenService.class);
    private final AuthService service = new AuthService(users, passwords, tokens);

    @Test
    void registerNormalizesUsernameAndHashesPassword() {
        AccessToken expected = new AccessToken("jwt", "Bearer", 7200, 7, "alice", "Alice");
        when(passwords.encode("very-secret")).thenReturn("bcrypt-hash");
        when(users.create("alice", "Alice", "bcrypt-hash")).thenReturn(7L);
        when(tokens.issue(new AuthUser(7, "alice", "Alice", "bcrypt-hash", "USER"))).thenReturn(expected);

        assertThat(service.register("  ALICE ", " Alice ", "very-secret")).isEqualTo(expected);
        verify(users).create("alice", "Alice", "bcrypt-hash");
    }

    @Test
    void duplicateUsernameIsRejectedBeforePasswordHashing() {
        when(users.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.register("Alice", "Alice", "very-secret"))
                .isInstanceOf(ConflictException.class);
        verify(passwords, never()).encode("very-secret");
    }

    @Test
    void legacyAccountCannotLogin() {
        when(users.findByUsername("legacy_1"))
                .thenReturn(Optional.of(new AuthUser(1, "legacy_1", "Old", "ACCOUNT_DISABLED", "USER")));

        assertThatThrownBy(() -> service.login("legacy_1", "anything"))
                .isInstanceOf(BadCredentialsException.class);
        verify(passwords, never()).matches("anything", "ACCOUNT_DISABLED");
    }
}
