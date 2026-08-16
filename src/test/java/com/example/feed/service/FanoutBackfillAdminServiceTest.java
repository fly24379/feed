package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.domain.FanoutBackfillStatus;
import com.example.feed.domain.FanoutMode;
import com.example.feed.repository.FanoutBackfillJobRepository;
import com.example.feed.repository.FanoutBackfillJobRepository.FanoutBackfillJob;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FanoutBackfillAdminServiceTest {
    private final FanoutBackfillJobRepository jobs = mock(FanoutBackfillJobRepository.class);
    private final FanoutBackfillAdminService service = new FanoutBackfillAdminService(jobs);

    @Test
    void pendingTaskCanBePaused() {
        when(jobs.find("job")).thenReturn(Optional.of(job(FanoutBackfillStatus.PENDING)))
                .thenReturn(Optional.of(job(FanoutBackfillStatus.PAUSED)));
        when(jobs.pause("job")).thenReturn(true);

        assertThat(service.pause("job").status()).isEqualTo(FanoutBackfillStatus.PAUSED);
        verify(jobs).pause("job");
    }

    @Test
    void pausedTaskCanResumeFromStoredCursor() {
        when(jobs.find("job")).thenReturn(Optional.of(job(FanoutBackfillStatus.PAUSED)))
                .thenReturn(Optional.of(job(FanoutBackfillStatus.PENDING)));
        when(jobs.resume("job")).thenReturn(true);

        assertThat(service.resume("job").status()).isEqualTo(FanoutBackfillStatus.PENDING);
    }

    @Test
    void failedTaskCannotRetryWhileAuthorHasAnotherActiveTask() {
        when(jobs.find("job")).thenReturn(Optional.of(job(FanoutBackfillStatus.FAILED)));
        when(jobs.hasActiveForAuthor(7)).thenReturn(true);

        assertThatThrownBy(() -> service.retry("job"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已有进行中的回填任务");
    }

    @Test
    void completedTaskCannotBeCancelled() {
        when(jobs.find("job")).thenReturn(Optional.of(job(FanoutBackfillStatus.COMPLETED)));
        when(jobs.cancel("job")).thenReturn(false);

        assertThatThrownBy(() -> service.cancel("job"))
                .isInstanceOf(ConflictException.class);
    }

    private FanoutBackfillJob job(FanoutBackfillStatus status) {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        return new FanoutBackfillJob("job", 7, FanoutMode.PUSH, FanoutMode.PULL,
                status, null, null, 100, 20, 0, now, "p20", 0, null,
                now, status == FanoutBackfillStatus.RUNNING ? now : null,
                status == FanoutBackfillStatus.RUNNING ? "worker" : null,
                99L, now, now, status == FanoutBackfillStatus.COMPLETED ? now : null, now);
    }
}
