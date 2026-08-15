package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.FanoutPolicyRepository.FanoutPolicy;
import com.example.feed.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FanoutPolicyServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final FanoutPolicyRepository policies = mock(FanoutPolicyRepository.class);
    private final FanoutPolicyService service = new FanoutPolicyService(users, policies);

    @Test
    void missingPolicyResolvesToImplicitPush() {
        when(policies.find(7)).thenReturn(Optional.empty());

        FanoutPolicy result = service.get(7);

        assertThat(result.mode()).isEqualTo(FanoutMode.PUSH);
        assertThat(result.explicit()).isFalse();
    }

    @Test
    void administratorCanSetAndResetExplicitPolicy() {
        FanoutPolicy stored = new FanoutPolicy(7, FanoutMode.PULL, "high degree",
                Instant.parse("2026-08-15T00:00:00Z"), true);
        when(policies.find(7)).thenReturn(Optional.of(stored));

        assertThat(service.set(7, FanoutMode.PULL, "high degree")).isEqualTo(stored);
        service.reset(7);

        verify(policies).upsert(7, FanoutMode.PULL, "high degree");
        verify(policies).delete(7);
    }
}
