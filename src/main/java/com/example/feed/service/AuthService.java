package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.repository.UserRepository;
import com.example.feed.repository.UserRepository.AuthUser;
import com.example.feed.security.JwtTokenService;
import com.example.feed.security.JwtTokenService.AccessToken;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    @Transactional
    public AccessToken register(String username, String nickname, String password) {
        String normalized = normalize(username);
        if (users.existsByUsername(normalized)) {
            throw new ConflictException("用户名已存在");
        }
        String passwordHash = passwordEncoder.encode(password);
        try {
            long userId = users.create(normalized, nickname.strip(), passwordHash);
            return tokens.issue(new AuthUser(userId, normalized, nickname.strip(), passwordHash, "USER"));
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("用户名已存在");
        }
    }

    @Transactional(readOnly = true)
    public AccessToken login(String username, String password) {
        AuthUser user = users.findByUsername(normalize(username))
                .orElseThrow(() -> new BadCredentialsException("invalid credentials"));
        if (!user.passwordHash().startsWith("$2") || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("invalid credentials");
        }
        return tokens.issue(user);
    }

    private String normalize(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }
}
