package com.example.feed.service;

import com.example.feed.domain.Post;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class FeedShadowVerifier {
    private static final Logger log = LoggerFactory.getLogger(FeedShadowVerifier.class);

    private final LegacyFeedQueryService legacy;
    private final boolean enabled;
    private final double sampleRate;
    private final Counter reads;
    private final Counter matches;
    private final Counter mismatches;
    private final Counter errors;
    private final Counter missingItems;
    private final Counter unexpectedItems;
    private final Counter duplicateItems;
    private final AtomicLong lastMissing = new AtomicLong();
    private final AtomicLong lastUnexpected = new AtomicLong();
    private final AtomicLong lastDuplicates = new AtomicLong();

    public FeedShadowVerifier(LegacyFeedQueryService legacy, MeterRegistry registry,
                              @Value("${feed.shadow.enabled:true}") boolean enabled,
                              @Value("${feed.shadow.sample-rate:0.1}") double sampleRate) {
        this.legacy = legacy;
        this.enabled = enabled;
        this.sampleRate = Math.max(0, Math.min(sampleRate, 1));
        this.reads = Counter.builder("feed.shadow.reads").register(registry);
        this.matches = Counter.builder("feed.shadow.matches").register(registry);
        this.mismatches = Counter.builder("feed.shadow.mismatches").register(registry);
        this.errors = Counter.builder("feed.shadow.errors").register(registry);
        this.missingItems = Counter.builder("feed.shadow.missing.items").register(registry);
        this.unexpectedItems = Counter.builder("feed.shadow.unexpected.items").register(registry);
        this.duplicateItems = Counter.builder("feed.shadow.duplicate.items").register(registry);
    }

    public void compareFirstPage(long viewerId, int size, List<Post> primary) {
        if (!enabled || ThreadLocalRandom.current().nextDouble() >= sampleRate) {
            return;
        }
        reads.increment();
        try {
            List<String> primaryIds = primary.stream().map(Post::id).toList();
            List<String> legacyIds = legacy.readFirstPage(viewerId, size).stream().map(Post::id).toList();
            Set<String> primarySet = new LinkedHashSet<>(primaryIds);
            Set<String> legacySet = new LinkedHashSet<>(legacyIds);
            Set<String> missing = new LinkedHashSet<>(legacySet);
            missing.removeAll(primarySet);
            Set<String> unexpected = new LinkedHashSet<>(primarySet);
            unexpected.removeAll(legacySet);
            int duplicates = primaryIds.size() - primarySet.size();
            lastMissing.set(missing.size());
            lastUnexpected.set(unexpected.size());
            lastDuplicates.set(duplicates);
            missingItems.increment(missing.size());
            unexpectedItems.increment(unexpected.size());
            duplicateItems.increment(duplicates);
            if (missing.isEmpty() && unexpected.isEmpty() && duplicates == 0
                    && primaryIds.equals(legacyIds)) {
                matches.increment();
            } else {
                mismatches.increment();
                log.warn("Feed shadow mismatch viewer={} missing={} unexpected={} duplicates={} primary={} legacy={}",
                        viewerId, missing, unexpected, duplicates, primaryIds, legacyIds);
            }
        } catch (RuntimeException exception) {
            errors.increment();
            log.warn("Feed shadow read failed for viewer {}", viewerId, exception);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(enabled, sampleRate, (long) reads.count(), (long) matches.count(),
                (long) mismatches.count(), (long) errors.count(), lastMissing.get(),
                lastUnexpected.get(), lastDuplicates.get());
    }

    public record Snapshot(boolean enabled, double sampleRate, long reads, long matches,
                           long mismatches, long errors, long lastMissing,
                           long lastUnexpected, long lastDuplicates) {
    }
}
