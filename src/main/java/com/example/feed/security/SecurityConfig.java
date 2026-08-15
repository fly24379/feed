package com.example.feed.security;

import com.example.feed.repository.AuthSessionRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final String secret;
    private final String issuer;

    public SecurityConfig(@Value("${feed.security.jwt.secret}") String secret,
                          @Value("${feed.security.jwt.issuer}") String issuer) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        this.secret = secret;
        this.issuer = issuer;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/revoke",
                                "/api/auth/verification/register/request",
                                "/api/auth/password-reset/request", "/api/auth/password-reset/confirm").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "Bearer token 缺失、无效或已过期")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "需要登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "无权访问该资源")))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey()));
    }

    @Bean
    JwtDecoder jwtDecoder(ObjectProvider<AuthSessionRepository> sessionRepositories) {
        return jwtDecoder(sessionRepositories.getIfAvailable());
    }

    JwtDecoder jwtDecoder() {
        return jwtDecoder((AuthSessionRepository) null);
    }

    JwtDecoder jwtDecoder(AuthSessionRepository sessions) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> subjectValidator = jwt -> {
            try {
                return Long.parseLong(jwt.getSubject()) > 0
                        ? OAuth2TokenValidatorResult.success()
                        : invalidSubject();
            } catch (RuntimeException exception) {
                return invalidSubject();
            }
        };
        OAuth2TokenValidator<Jwt> sessionValidator = jwt -> {
            String sessionId = jwt.getClaimAsString("sid");
            if (sessionId == null || sessionId.isBlank()) {
                return invalidSession();
            }
            try {
                return sessions == null || sessions.isActive(sessionId)
                        ? OAuth2TokenValidatorResult.success()
                        : invalidSession();
            } catch (RuntimeException exception) {
                return invalidSession();
            }
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                defaultValidator, subjectValidator, sessionValidator));
        return decoder;
    }

    private OAuth2TokenValidatorResult invalidSubject() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "JWT subject must be a positive user id", null));
    }

    private OAuth2TokenValidatorResult invalidSession() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "JWT session is missing, expired, or revoked", null));
    }

    private SecretKey secretKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private void writeProblem(HttpServletResponse response, int status, String detail) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
