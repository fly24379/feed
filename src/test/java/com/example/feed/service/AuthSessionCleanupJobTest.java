package com.example.feed.service;

import com.example.feed.repository.AuthSessionRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthSessionCleanupJobTest {
    @Test
    void removesSessionsOnlyAfterRetentionPeriod() {
        AuthSessionRepository sessions = mock(AuthSessionRepository.class);
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        AuthSessionCleanupJob job = new AuthSessionCleanupJob(
                sessions, Duration.ofDays(7), Clock.fixed(now, ZoneOffset.UTC));

        job.clean();

        verify(sessions).deleteExpiredOrRevokedBefore(now.minus(Duration.ofDays(7)));
    }
}
