package com.example.feed.service;

import com.example.feed.repository.OutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
public class OutboxRecoveryJob {
    private final OutboxRepository outbox;
    private final OutboxBackoff backoff;
    private final Duration processingTimeout;
    private final int batchSize;
    private final int maxAttempts;
    private final Clock clock = Clock.systemUTC();

    public OutboxRecoveryJob(OutboxRepository outbox, OutboxBackoff backoff,
                             @Value("${feed.fanout.processing-timeout:2m}") Duration processingTimeout,
                             @Value("${feed.fanout.batch-size:50}") int batchSize,
                             @Value("${feed.fanout.max-attempts:8}") int maxAttempts) {
        this.outbox = outbox;
        this.backoff = backoff;
        this.processingTimeout = processingTimeout;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${feed.fanout.recovery-delay-ms:10000}")
    public void recover() {
        var now = clock.instant();
        for (OutboxRepository.TimedOutEvent event : outbox.findTimedOut(now.minus(processingTimeout), batchSize)) {
            outbox.recoverTimedOut(event, now.plus(backoff.forAttempt(event.attempts())), maxAttempts);
        }
    }
}
