package com.example.feed.service;

import com.example.feed.domain.FeedCandidate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;

class AuthorTimelineCacheTest {
    @Test
    void redisFailureFallsBackToDatabaseLoader() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("feed:author-timeline:v1:7:meta")).thenThrow(new IllegalStateException("redis down"));
        AuthorTimelineCache cache = new AuthorTimelineCache(
                redis, new SimpleMeterRegistry(), Duration.ofMinutes(5), 100);
        FeedCandidate expected = new FeedCandidate("p1", Instant.parse("2026-08-16T00:00:00Z"));

        List<FeedCandidate> result = cache.findPage(7, null, 20,
                (cursor, limit) -> List.of(expected));

        assertThat(result).containsExactly(expected);
    }

    @Test
    void appendEncodesMicrosecondTimestampAsSortableIntegerMember() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked") ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.zCard("feed:author-timeline:v1:7")).thenReturn(1L);
        AuthorTimelineCache cache = new AuthorTimelineCache(
                redis, new SimpleMeterRegistry(), Duration.ofMinutes(5), 100);

        cache.append(7, new FeedCandidate("p1", Instant.parse("2026-08-16T00:00:00.123456Z")));

        verify(zsets).add(eq("feed:author-timeline:v1:7"), contains("|p1"), anyDouble());
    }
}
