package com.example.feed.service;

import com.example.feed.repository.FeedInboxArchiveRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedInboxRetentionJobTest {
    private final FeedInboxArchiveRepository archive = mock(FeedInboxArchiveRepository.class);
    private final TransactionOperations transaction = mock(TransactionOperations.class);
    private final Instant now = Instant.parse("2026-08-15T00:00:00Z");

    @Test
    void archivesUntilTheBatchIsNotFull() {
        Instant cutoff = now.minus(Duration.ofDays(90)).truncatedTo(ChronoUnit.MICROS);
        runTransactionCallbacks();
        when(archive.archiveNextBatch(cutoff, 100)).thenReturn(100, 23);
        FeedInboxRetentionJob job = job(Duration.ofDays(90), 100, 5);

        job.archiveColdInbox();

        verify(archive, org.mockito.Mockito.times(2)).archiveNextBatch(cutoff, 100);
    }

    @Test
    void capsWorkPerformedInOneScheduledRun() {
        Instant cutoff = now.minus(Duration.ofDays(180)).truncatedTo(ChronoUnit.MICROS);
        runTransactionCallbacks();
        when(archive.archiveNextBatch(cutoff, 10)).thenReturn(10, 10, 10);
        FeedInboxRetentionJob job = job(Duration.ofDays(180), 10, 3);

        job.archiveColdInbox();

        verify(archive, org.mockito.Mockito.times(3)).archiveNextBatch(cutoff, 10);
    }

    @Test
    void rejectsNonPositiveHotRetention() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> job(Duration.ZERO, 10, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void runTransactionCallbacks() {
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private FeedInboxRetentionJob job(Duration hotRetention, int batchSize, int maxBatches) {
        return new FeedInboxRetentionJob(archive, transaction, hotRetention, batchSize, maxBatches,
                Clock.fixed(now, ZoneOffset.UTC));
    }
}
