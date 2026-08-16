package com.example.feed.service;

import com.example.feed.domain.FanoutBackfillStatus;
import com.example.feed.domain.FanoutMode;
import com.example.feed.repository.FanoutBackfillJobRepository;
import com.example.feed.repository.FanoutBackfillJobRepository.FanoutBackfillJob;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FanoutBackfillWorkerTest {
    private final FanoutBackfillJobRepository jobs = mock(FanoutBackfillJobRepository.class);
    private final FanoutBackfillBatchService batches = mock(FanoutBackfillBatchService.class);

    @Test
    void batchFailureMovesClaimedTaskToFailedForManualRetry() {
        FanoutBackfillJob job = job();
        when(jobs.claimNext(anyString())).thenReturn(Optional.of(job));
        when(batches.processBatch(eq("job"), anyString(), eq(50)))
                .thenThrow(new IllegalStateException("database unavailable"));
        FanoutBackfillWorker worker = new FanoutBackfillWorker(jobs, batches, 50, 1,
                Duration.ofMinutes(5));

        worker.run();

        verify(jobs).recoverTimedOut(any(Instant.class));
        verify(jobs).markFailed(eq("job"), anyString(),
                eq("IllegalStateException: database unavailable"));
    }

    @Test
    void noPendingTaskStopsWithoutCallingBatchProcessor() {
        when(jobs.claimNext(anyString())).thenReturn(Optional.empty());
        FanoutBackfillWorker worker = new FanoutBackfillWorker(jobs, batches, 50, 3,
                Duration.ofMinutes(5));

        worker.run();

        verify(jobs).recoverTimedOut(any(Instant.class));
        verify(jobs).claimNext(anyString());
    }

    private FanoutBackfillJob job() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        return new FanoutBackfillJob("job", 7, FanoutMode.PUSH, FanoutMode.PULL,
                FanoutBackfillStatus.RUNNING, null, null, 100, 0, 0,
                null, null, 0, null, now, now, "ignored-by-worker", 99L,
                now, now, null, now);
    }
}
