package com.example.feed.service;

import com.example.feed.repository.FanoutBackfillJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Component
public class FanoutBackfillWorker {
    private final FanoutBackfillJobRepository jobs;
    private final FanoutBackfillBatchService batches;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final Duration processingTimeout;
    private final Clock clock = Clock.systemUTC();
    private final String instanceId = "backfill:" + UUID.randomUUID();

    public FanoutBackfillWorker(FanoutBackfillJobRepository jobs, FanoutBackfillBatchService batches,
                                @Value("${feed.fanout.backfill.batch-size:500}") int batchSize,
                                @Value("${feed.fanout.backfill.max-batches-per-run:10}") int maxBatchesPerRun,
                                @Value("${feed.fanout.backfill.processing-timeout:5m}")
                                Duration processingTimeout) {
        if (batchSize < 1 || maxBatchesPerRun < 1) {
            throw new IllegalArgumentException("回填批次参数必须大于 0");
        }
        this.jobs = jobs;
        this.batches = batches;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.processingTimeout = processingTimeout;
    }

    @Scheduled(fixedDelayString = "${feed.fanout.backfill.delay-ms:1000}",
            initialDelayString = "${feed.fanout.backfill.initial-delay-ms:5000}")
    public void run() {
        jobs.recoverTimedOut(clock.instant().minus(processingTimeout));
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            var claimed = jobs.claimNext(instanceId);
            if (claimed.isEmpty()) {
                return;
            }
            try {
                batches.processBatch(claimed.get().id(), instanceId, batchSize);
            } catch (RuntimeException exception) {
                jobs.markFailed(claimed.get().id(), instanceId, rootMessage(exception));
            }
        }
    }

    private String rootMessage(RuntimeException exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }
}
