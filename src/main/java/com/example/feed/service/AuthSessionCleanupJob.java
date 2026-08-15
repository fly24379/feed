package com.example.feed.service;

import com.example.feed.repository.AuthSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

@Component
public class AuthSessionCleanupJob {
    private final AuthSessionRepository sessions;
    private final Duration retention;
    private final Clock clock;

    @Autowired
    public AuthSessionCleanupJob(AuthSessionRepository sessions,
                                 @Value("${feed.security.session-retention:7d}") Duration retention) {
        this(sessions, retention, Clock.systemUTC());
    }

    AuthSessionCleanupJob(AuthSessionRepository sessions, Duration retention, Clock clock) {
        this.sessions = sessions;
        this.retention = retention;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${feed.security.session-cleanup-initial-delay-ms:300000}",
            fixedDelayString = "${feed.security.session-cleanup-delay-ms:86400000}")
    @Transactional
    public void clean() {
        sessions.deleteExpiredOrRevokedBefore(clock.instant().minus(retention));
    }
}
