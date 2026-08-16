package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicySource;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.FanoutPolicyRepository.FanoutPolicy;
import com.example.feed.repository.FanoutRepository;
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

class FanoutPolicyServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final FanoutPolicyRepository policies = mock(FanoutPolicyRepository.class);
    private final PostRepository posts = mock(PostRepository.class);
    private final FanoutRepository fanout = mock(FanoutRepository.class);
    private final FanoutPolicyService service = new FanoutPolicyService(users, policies, posts, fanout);

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
    void switchingToPullReclassifiesHistoryWithoutWritingInbox() {
        FanoutPolicy stored = policy(FanoutMode.PULL, "high degree");
        when(policies.resolveMode(7)).thenReturn(FanoutMode.PUSH);
        when(posts.findRecentPostIdsForModeChange(7, FanoutMode.PULL, 100))
                .thenReturn(java.util.List.of("p2", "p1"));
        when(posts.updateDeliveryMode(java.util.List.of("p2", "p1"), FanoutMode.PULL)).thenReturn(2);
        when(policies.find(7)).thenReturn(Optional.of(stored));

        var result = service.switchMode(7, FanoutMode.PULL, "high degree", 100);

        assertThat(result.previousMode()).isEqualTo(FanoutMode.PUSH);
        assertThat(result.historyUpdated()).isEqualTo(2);
        assertThat(result.inboxRowsInserted()).isZero();
        verify(fanout, never()).fanoutPosts(java.util.List.of("p2", "p1"));
    }

    @Test
    void switchingBackToPushRebuildsFriendInboxesIdempotently() {
        FanoutPolicy stored = policy(FanoutMode.PUSH, "normal author");
        var postIds = java.util.List.of("p2", "p1");
        when(policies.resolveMode(7)).thenReturn(FanoutMode.PULL);
        when(posts.findRecentPostIdsForModeChange(7, FanoutMode.PUSH, 100)).thenReturn(postIds);
        when(posts.updateDeliveryMode(postIds, FanoutMode.PUSH)).thenReturn(2);
        when(fanout.fanoutPosts(postIds)).thenReturn(8);
        when(policies.find(7)).thenReturn(Optional.of(stored));

        var result = service.switchMode(7, FanoutMode.PUSH, "normal author", 100);

        assertThat(result.previousMode()).isEqualTo(FanoutMode.PULL);
        assertThat(result.historyUpdated()).isEqualTo(2);
        assertThat(result.inboxRowsInserted()).isEqualTo(8);
        verify(fanout).fanoutPosts(postIds);
    }

    private FanoutPolicy policy(FanoutMode mode, String reason) {
        return new FanoutPolicy(7, mode, FanoutPolicySource.MANUAL, reason, null, null,
                Instant.parse("2026-08-15T00:00:00Z"), true);
    }
}
