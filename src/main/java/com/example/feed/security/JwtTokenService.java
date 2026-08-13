package com.example.feed.security;

import com.example.feed.repository.UserRepository.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final Duration ttl;

    @Autowired
    public JwtTokenService(JwtEncoder encoder,
                           @Value("${feed.security.jwt.issuer}") String issuer,
                           @Value("${feed.security.jwt.ttl:2h}") Duration ttl) {
        this(encoder, Clock.systemUTC(), issuer, ttl);
    }

    JwtTokenService(JwtEncoder encoder, Clock clock, String issuer, Duration ttl) {
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    public AccessToken issue(AuthUser user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(Long.toString(user.id()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("username", user.username())
                .claim("nickname", user.nickname())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, "Bearer", ttl.toSeconds(), user.id(), user.username(), user.nickname());
    }

    public record AccessToken(String accessToken, String tokenType, long expiresIn,
                              long userId, String username, String nickname) {
    }
}
