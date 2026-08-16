package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicySource;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.FanoutPolicyRepository.FanoutPolicy;
import com.example.feed.repository.RelationshipRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FanoutAutoPolicyJobTest {
    private final RelationshipRepository relationships = mock(RelationshipRepository.class);
    private final FanoutPolicyRepository policies = mock(FanoutPolicyRepository.class);

    @Test
    void appliesHysteresisAndNeverOverwritesManualPolicy() {
        when(relationships.findConnectionCountsAfter(0, 10)).thenReturn(List.of(
                count(1, 120), count(2, 90), count(3, 70), count(4, 200)));
        when(policies.find(1)).thenReturn(Optional.empty());
        when(policies.find(2)).thenReturn(Optional.of(autoPolicy(2)));
        when(policies.find(3)).thenReturn(Optional.of(autoPolicy(3)));
        when(policies.find(4)).thenReturn(Optional.of(manualPolicy(4)));
        FanoutAutoPolicyJob job = new FanoutAutoPolicyJob(relationships, policies,
                new SimpleMeterRegistry(), true, 100, 80, 10, 2);

        var result = job.refresh();

        assertThat(result.evaluatedThisRun()).isEqualTo(4);
        assertThat(result.promotedThisRun()).isEqualTo(1);
        assertThat(result.revertedThisRun()).isEqualTo(1);
        verify(policies).upsertAuto(1, FanoutMode.PULL, 120);
        verify(policies).upsertAuto(2, FanoutMode.PULL, 90);
        verify(policies).deleteAuto(3);
        verify(policies, never()).upsertAuto(4, FanoutMode.PULL, 200);
    }

    private RelationshipRepository.ConnectionCount count(long id, long count) {
        return new RelationshipRepository.ConnectionCount(id, count);
    }

    private FanoutPolicy autoPolicy(long id) {
        return new FanoutPolicy(id, FanoutMode.PULL, FanoutPolicySource.AUTO,
                "automatic", 90L, Instant.now(), Instant.now(), true);
    }

    private FanoutPolicy manualPolicy(long id) {
        return new FanoutPolicy(id, FanoutMode.PUSH, FanoutPolicySource.MANUAL,
                "manual", null, null, Instant.now(), true);
    }
}
