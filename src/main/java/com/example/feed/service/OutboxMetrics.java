package com.example.feed.service;

import com.example.feed.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxMetrics {
    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);
    private final OutboxRepository outbox;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong oldestAgeMillis = new AtomicLong();
    private final AtomicLong processingLatencyMillis = new AtomicLong();

    public OutboxMetrics(OutboxRepository outbox, MeterRegistry registry) {
        this.outbox = outbox;
        Gauge.builder("feed.outbox.backlog", backlog, AtomicLong::get)
                .description("Outbox events awaiting successful fanout").register(registry);
        Gauge.builder("feed.outbox.failed", failed, AtomicLong::get)
                .description("Outbox events in dead-letter FAILED state").register(registry);
        Gauge.builder("feed.outbox.oldest.age.seconds", oldestAgeMillis,
                        value -> value.get() / 1000.0)
                .description("Age of oldest unfinished outbox event").register(registry);
        Gauge.builder("feed.outbox.processing.latency.seconds", processingLatencyMillis,
                        value -> value.get() / 1000.0)
                .description("Average end-to-end latency of events processed in last five minutes")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${feed.fanout.metrics-delay-ms:5000}")
    public void refresh() {
        try {
            backlog.set(outbox.countBacklog());
            failed.set(outbox.countFailed());
            oldestAgeMillis.set(Math.round(outbox.oldestBacklogAgeSeconds() * 1000));
            processingLatencyMillis.set(Math.round(outbox.recentAverageProcessingLatencySeconds() * 1000));
        } catch (RuntimeException exception) {
            log.warn("Failed to refresh outbox metrics", exception);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(backlog.get(), failed.get(), oldestAgeMillis.get() / 1000.0,
                processingLatencyMillis.get() / 1000.0);
    }

    public record Snapshot(long backlog, long failed, double oldestBacklogAgeSeconds,
                           double averageProcessingLatencySeconds) {
    }
}
