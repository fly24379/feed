package com.example.feed.service;

import com.example.feed.repository.FeedInboxArchiveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Moves aged Inbox candidates to cold storage; homepage reads stay on the hot table. */
@Component
public class FeedInboxRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(FeedInboxRetentionJob.class);

    private final FeedInboxArchiveRepository archive;
    private final TransactionOperations transaction;
    private final Duration hotRetention;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final Clock clock;

    @Autowired
    public FeedInboxRetentionJob(FeedInboxArchiveRepository archive, TransactionOperations transaction,
                                 @Value("${feed.inbox.hot-retention:90d}") Duration hotRetention,
                                 @Value("${feed.inbox.archive-batch-size:500}") int batchSize,
                                 @Value("${feed.inbox.archive-max-batches-per-run:10}") int maxBatchesPerRun) {
        this(archive, transaction, hotRetention, batchSize, maxBatchesPerRun, Clock.systemUTC());
    }

    FeedInboxRetentionJob(FeedInboxArchiveRepository archive, TransactionOperations transaction,
                          Duration hotRetention, int batchSize, int maxBatchesPerRun, Clock clock) {
        if (hotRetention.isNegative() || hotRetention.isZero()
                || batchSize < 1 || maxBatchesPerRun < 1) {
            throw new IllegalArgumentException("Inbox 保留参数不合法");
        }
        this.archive = archive;
        this.transaction = transaction;
        this.hotRetention = hotRetention;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${feed.inbox.archive-initial-delay-ms:300000}",
            fixedDelayString = "${feed.inbox.archive-delay-ms:60000}")
    public void archiveColdInbox() {
        Instant cutoff = clock.instant().minus(hotRetention).truncatedTo(ChronoUnit.MICROS);
        int archived = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            Integer moved = transaction.execute(status -> archive.archiveNextBatch(cutoff, batchSize));
            int movedCount = moved == null ? 0 : moved;
            archived += movedCount;
            if (movedCount < batchSize) {
                break;
            }
        }
        if (archived > 0) {
            log.info("Archived {} Inbox entries published before {}", archived, cutoff);
        }
    }
}
