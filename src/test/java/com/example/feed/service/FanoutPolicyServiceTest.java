package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicySource;
import com.example.feed.domain.FanoutBackfillStatus;
import com.example.feed.repository.FanoutBackfillJobRepository;
import com.example.feed.repository.FanoutBackfillJobRepository.FanoutBackfillJob;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.FanoutPolicyRepository.FanoutPolicy;
import com.example.feed.repository.PostRepository;
import com.example.feed.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class FanoutPolicyServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final FanoutPolicyRepository policies = mock(FanoutPolicyRepository.class);
    private final PostRepository posts = mock(PostRepository.class);
    private final FanoutBackfillJobRepository backfills = mock(FanoutBackfillJobRepository.class);
    private final FanoutPolicyService service = new FanoutPolicyService(
            users, policies, posts, backfills);

    @Test
    void missingPolicyResolvesToImplicitPush() {
        when(policies.find(7)).thenReturn(Optional.empty());

        FanoutPolicy result = service.get(7);

        assertThat(result.mode()).isEqualTo(FanoutMode.PUSH);
        assertThat(result.explicit()).isFalse();
    }

    @Test
    void administratorCanSetAndResetExplicitPolicy() {
        FanoutPolicy stored = policy(FanoutMode.PULL, "high degree");
        when(policies.find(7)).thenReturn(Optional.of(stored));

        assertThat(service.set(7, FanoutMode.PULL, "high degree")).isEqualTo(stored);
        service.reset(7);

        verify(policies).upsert(7, FanoutMode.PULL, "high degree");
        verify(policies).delete(7);
    }

    @Test
    void switchingToPullCreatesAsynchronousBackfillWithoutWritingHistoryInline() {
        FanoutPolicy stored = policy(FanoutMode.PULL, "high degree");
        FanoutBackfillJob job = job(FanoutMode.PUSH, FanoutMode.PULL, 100);
        when(policies.resolveMode(7)).thenReturn(FanoutMode.PUSH);
        when(posts.countPostsForModeChange(7, FanoutMode.PULL)).thenReturn(400L);
        when(backfills.create(anyString(), eq(7L), eq(FanoutMode.PUSH), eq(FanoutMode.PULL),
                eq("high degree"), eq(100L), eq(100L), eq(null))).thenReturn(job);
        when(policies.find(7)).thenReturn(Optional.of(stored));

        var result = service.switchMode(7, FanoutMode.PULL, "high degree", 100);

        assertThat(result.previousMode()).isEqualTo(FanoutMode.PUSH);
        assertThat(result.backfillJob()).isEqualTo(job);
        verify(posts, never()).updateDeliveryMode(org.mockito.ArgumentMatchers.any(), eq(FanoutMode.PULL));
    }

    @Test
    void nullHistoryLimitQueuesAllEligibleHistory() {
        FanoutPolicy stored = policy(FanoutMode.PUSH, "normal author");
        FanoutBackfillJob job = job(FanoutMode.PULL, FanoutMode.PUSH, 12_000);
        when(policies.resolveMode(7)).thenReturn(FanoutMode.PULL);
        when(posts.countPostsForModeChange(7, FanoutMode.PUSH)).thenReturn(12_000L);
        when(backfills.create(anyString(), eq(7L), eq(FanoutMode.PULL), eq(FanoutMode.PUSH),
                eq("normal author"), eq(null), eq(12_000L), eq(99L))).thenReturn(job);
        when(policies.find(7)).thenReturn(Optional.of(stored));

        var result = service.switchMode(7, FanoutMode.PUSH, "normal author", null, 99L);

        assertThat(result.backfillJob().totalPosts()).isEqualTo(12_000);
        verify(backfills).create(anyString(), eq(7L), eq(FanoutMode.PULL), eq(FanoutMode.PUSH),
                eq("normal author"), eq(null), eq(12_000L), eq(99L));
    }

    private FanoutPolicy policy(FanoutMode mode, String reason) {
        return new FanoutPolicy(7, mode, FanoutPolicySource.MANUAL, reason, null, null,
                Instant.parse("2026-08-15T00:00:00Z"), true);
    }

    private FanoutBackfillJob job(FanoutMode source, FanoutMode target, long total) {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        return new FanoutBackfillJob("job-1", 7, source, target, FanoutBackfillStatus.PENDING,
                "reason", null, total, 0, 0, null, null, 0, null,
                now, null, null, 99L, now, null, null, now);
    }
}
