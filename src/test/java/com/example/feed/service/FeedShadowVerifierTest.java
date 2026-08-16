package com.example.feed.service;

import com.example.feed.domain.Post;
import com.example.feed.domain.PostStatus;
import com.example.feed.domain.Visibility;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedShadowVerifierTest {
    private final LegacyFeedQueryService legacy = mock(LegacyFeedQueryService.class);

    @Test
    void recordsExactMatches() {
        Post p1 = post("p1");
        when(legacy.readFirstPage(7, 20)).thenReturn(List.of(p1));
        FeedShadowVerifier verifier = new FeedShadowVerifier(
                legacy, new SimpleMeterRegistry(), true, 1.0);

        verifier.compareFirstPage(7, 20, List.of(p1));

        assertThat(verifier.snapshot().matches()).isEqualTo(1);
        assertThat(verifier.snapshot().mismatches()).isZero();
    }

    @Test
    void recordsMissingAndDuplicateItemsWithoutThrowing() {
        Post p1 = post("p1");
        Post p2 = post("p2");
        when(legacy.readFirstPage(7, 20)).thenReturn(List.of(p1, p2));
        FeedShadowVerifier verifier = new FeedShadowVerifier(
                legacy, new SimpleMeterRegistry(), true, 1.0);

        verifier.compareFirstPage(7, 20, List.of(p1, p1));

        assertThat(verifier.snapshot().mismatches()).isEqualTo(1);
        assertThat(verifier.snapshot().lastMissing()).isEqualTo(1);
        assertThat(verifier.snapshot().lastDuplicates()).isEqualTo(1);
    }

    private Post post(String id) {
        return new Post(id, 1, "content", Visibility.ALL_FRIENDS,
                PostStatus.ACTIVE, Instant.parse("2026-08-16T00:00:00Z"));
    }
}
