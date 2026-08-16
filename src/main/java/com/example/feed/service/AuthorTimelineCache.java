package com.example.feed.service;

import com.example.feed.domain.FeedCandidate;
import com.example.feed.domain.FeedCursor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

@Component
public class AuthorTimelineCache {
    private static final Logger log = LoggerFactory.getLogger(AuthorTimelineCache.class);
    private static final String PREFIX = "feed:author-timeline:v1:";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final int maxEntries;
    private final Counter hits;
    private final Counter misses;
    private final Counter fallbacks;
    private final Counter appends;

    public AuthorTimelineCache(StringRedisTemplate redis, MeterRegistry registry,
                               @Value("${feed.cache.author-timeline-ttl:5m}") Duration ttl,
                               @Value("${feed.cache.author-timeline-max-entries:500}") int maxEntries) {
        this.redis = redis;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.hits = Counter.builder("feed.author.timeline.cache.hits").register(registry);
        this.misses = Counter.builder("feed.author.timeline.cache.misses").register(registry);
        this.fallbacks = Counter.builder("feed.author.timeline.cache.fallbacks").register(registry);
        this.appends = Counter.builder("feed.author.timeline.cache.appends").register(registry);
    }

    public List<FeedCandidate> findPage(long authorId, FeedCursor cursor, int limit,
                                        BiFunction<FeedCursor, Integer, List<FeedCandidate>> loader) {
        try {
            CachedTimeline cached = read(authorId);
            if (cached == null) {
                misses.increment();
                List<FeedCandidate> loaded = loader.apply(null, maxEntries);
                write(authorId, loaded, loaded.size() < maxEntries);
                cached = new CachedTimeline(loaded, loaded.size() < maxEntries);
            } else {
                hits.increment();
            }
            List<FeedCandidate> page = after(cached.items(), cursor, limit);
            if (page.size() >= limit || cached.complete()) {
                return page;
            }
        } catch (RuntimeException exception) {
            log.debug("Redis author timeline failed; falling back to MySQL for author {}", authorId, exception);
        }
        fallbacks.increment();
        return loader.apply(cursor, limit);
    }

    public void append(long authorId, FeedCandidate candidate) {
        try {
            String timelineKey = timelineKey(authorId);
            redis.opsForZSet().add(timelineKey, encode(candidate), epochMicros(candidate.publishedAt()));
            Long size = redis.opsForZSet().zCard(timelineKey);
            if (size != null && size > maxEntries) {
                redis.opsForZSet().removeRange(timelineKey, 0, size - maxEntries - 1);
            }
            redis.opsForValue().set(metaKey(authorId), "0", ttl);
            redis.expire(timelineKey, ttl);
            appends.increment();
        } catch (RuntimeException exception) {
            log.warn("Redis author timeline append failed for author {}", authorId, exception);
        }
    }

    public void evict(long authorId) {
        try {
            redis.delete(List.of(timelineKey(authorId), metaKey(authorId)));
        } catch (RuntimeException exception) {
            log.debug("Redis author timeline eviction failed for author {}", authorId, exception);
        }
    }

    private CachedTimeline read(long authorId) {
        String complete = redis.opsForValue().get(metaKey(authorId));
        if (complete == null) {
            return null;
        }
        Set<String> members = redis.opsForZSet().reverseRange(timelineKey(authorId), 0, maxEntries - 1);
        List<FeedCandidate> items = members == null
                ? List.of() : members.stream().map(this::decode).toList();
        return new CachedTimeline(items, "1".equals(complete));
    }

    private void write(long authorId, List<FeedCandidate> items, boolean complete) {
        String timelineKey = timelineKey(authorId);
        redis.delete(timelineKey);
        for (FeedCandidate item : items) {
            redis.opsForZSet().add(timelineKey, encode(item), epochMicros(item.publishedAt()));
        }
        redis.opsForValue().set(metaKey(authorId), complete ? "1" : "0", ttl);
        if (!items.isEmpty()) {
            redis.expire(timelineKey, ttl);
        }
    }

    private List<FeedCandidate> after(List<FeedCandidate> items, FeedCursor cursor, int limit) {
        List<FeedCandidate> result = new ArrayList<>(limit);
        for (FeedCandidate item : items) {
            if (cursor == null || item.publishedAt().isBefore(cursor.publishedAt())
                    || item.publishedAt().equals(cursor.publishedAt())
                    && item.postId().compareTo(cursor.postId()) < 0) {
                result.add(item);
                if (result.size() == limit) {
                    break;
                }
            }
        }
        return result;
    }

    private String encode(FeedCandidate candidate) {
        return String.format("%020d|%s", epochMicros(candidate.publishedAt()), candidate.postId());
    }

    private FeedCandidate decode(String member) {
        int separator = member.indexOf('|');
        long micros = Long.parseLong(member.substring(0, separator));
        Instant time = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                Math.floorMod(micros, 1_000_000L) * 1_000L);
        return new FeedCandidate(member.substring(separator + 1), time);
    }

    private long epochMicros(Instant value) {
        return Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000L),
                value.getNano() / 1_000L);
    }

    private String timelineKey(long authorId) {
        return PREFIX + authorId;
    }

    private String metaKey(long authorId) {
        return PREFIX + authorId + ":meta";
    }

    private record CachedTimeline(List<FeedCandidate> items, boolean complete) {
    }
}
