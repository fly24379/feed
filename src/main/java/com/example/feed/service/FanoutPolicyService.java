package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicySource;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.FanoutPolicyRepository.FanoutPolicy;
import com.example.feed.repository.FanoutBackfillJobRepository;
import com.example.feed.repository.FanoutBackfillJobRepository.FanoutBackfillJob;
import com.example.feed.repository.PostRepository;
import com.example.feed.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class FanoutPolicyService {
    private final UserRepository users;
    private final FanoutPolicyRepository policies;
    private final PostRepository posts;
    private final FanoutBackfillJobRepository backfills;

    public FanoutPolicyService(UserRepository users, FanoutPolicyRepository policies,
                               PostRepository posts,
                               FanoutBackfillJobRepository backfills) {
        this.users = users;
        this.policies = policies;
        this.posts = posts;
        this.backfills = backfills;
    }

    @Transactional(readOnly = true)
    public FanoutPolicy get(long authorId) {
        users.requireExists(authorId);
        return policies.find(authorId).orElseGet(() -> FanoutPolicy.defaultPush(authorId));
    }

    @Transactional
    public FanoutPolicy set(long authorId, FanoutMode mode, String reason) {
        users.requireExistsForUpdate(authorId);
        requireNoActiveBackfill(authorId);
        policies.upsert(authorId, mode, reason);
        return policies.find(authorId)
                .orElseThrow(() -> new IllegalStateException("扩散策略写入后无法读取"));
    }

    @Transactional
    public FanoutSwitchResult switchMode(long authorId, FanoutMode mode, String reason,
                                         Long historyLimit, Long createdBy) {
        users.requireExistsForUpdate(authorId);
        requireNoActiveBackfill(authorId);
        if (historyLimit != null && historyLimit < 0) {
            throw new IllegalArgumentException("历史回填数量不能为负数");
        }
        FanoutMode previousMode = policies.resolveMode(authorId);
        policies.upsert(authorId, mode, reason);
        long available = posts.countPostsForModeChange(authorId, mode);
        long totalPosts = historyLimit == null ? available : Math.min(available, historyLimit);
        FanoutBackfillJob job = backfills.create(UUID.randomUUID().toString(), authorId,
                previousMode, mode, reason, historyLimit, totalPosts, createdBy);
        FanoutPolicy policy = policies.find(authorId)
                .orElseThrow(() -> new IllegalStateException("扩散策略写入后无法读取"));
        return new FanoutSwitchResult(previousMode, policy, job);
    }

    /**
     * Applies an automatic policy decision and, when it changes the effective mode, creates the
     * same durable history migration used by a manual mode switch. The author row lock serializes
     * this decision with publishing and administrator-driven mode changes.
     */
    @Transactional
    public AutoTransitionResult reconcileAuto(long authorId, long followerCount, FanoutMode desiredMode,
                                              Long historyLimit) {
        if (followerCount < 0) {
            throw new IllegalArgumentException("粉丝数不能为负数");
        }
        if (historyLimit != null && historyLimit < 0) {
            throw new IllegalArgumentException("自动历史回填数量不能为负数");
        }

        users.requireExistsForUpdate(authorId);
        FanoutPolicy currentPolicy = policies.find(authorId).orElse(null);
        if (currentPolicy != null && currentPolicy.source() == FanoutPolicySource.MANUAL) {
            return AutoTransitionResult.manualOverride(currentPolicy.mode());
        }

        FanoutMode previousMode = currentPolicy == null ? FanoutMode.PUSH : currentPolicy.mode();
        if (backfills.hasActiveForAuthor(authorId)) {
            return AutoTransitionResult.deferred(previousMode, desiredMode);
        }

        if (previousMode == desiredMode) {
            if (desiredMode == FanoutMode.PULL) {
                // Refresh the observed follower count and evaluation timestamp without changing mode.
                policies.upsertAuto(authorId, FanoutMode.PULL, followerCount);
            }
            return AutoTransitionResult.unchanged(previousMode);
        }

        String reason = "automatic follower threshold: " + followerCount;
        if (desiredMode == FanoutMode.PULL) {
            policies.upsertAuto(authorId, FanoutMode.PULL, followerCount);
        } else {
            // The absence of an automatic policy is the default PUSH policy.
            policies.deleteAuto(authorId);
        }

        long available = posts.countPostsForModeChange(authorId, desiredMode);
        long totalPosts = historyLimit == null ? available : Math.min(available, historyLimit);
        FanoutBackfillJob job = backfills.create(UUID.randomUUID().toString(), authorId,
                previousMode, desiredMode, reason, historyLimit, totalPosts, null);
        return AutoTransitionResult.transitioned(previousMode, desiredMode, job);
    }

    FanoutSwitchResult switchMode(long authorId, FanoutMode mode, String reason, long historyLimit) {
        return switchMode(authorId, mode, reason, historyLimit, null);
    }

    @Transactional
    public void reset(long authorId) {
        users.requireExistsForUpdate(authorId);
        requireNoActiveBackfill(authorId);
        policies.delete(authorId);
    }

    private void requireNoActiveBackfill(long authorId) {
        if (backfills != null && backfills.hasActiveForAuthor(authorId)) {
            throw new ConflictException("该作者已有进行中的回填任务，请先完成或取消任务");
        }
    }

    public record FanoutSwitchResult(FanoutMode previousMode, FanoutPolicy policy,
                                     FanoutBackfillJob backfillJob) {
    }

    public record AutoTransitionResult(FanoutMode previousMode, FanoutMode targetMode,
                                       boolean transitioned, boolean deferred,
                                       boolean skippedManualPolicy,
                                       FanoutBackfillJob backfillJob) {
        static AutoTransitionResult transitioned(FanoutMode previousMode, FanoutMode targetMode,
                                                 FanoutBackfillJob job) {
            return new AutoTransitionResult(previousMode, targetMode, true, false, false, job);
        }

        static AutoTransitionResult unchanged(FanoutMode mode) {
            return new AutoTransitionResult(mode, mode, false, false, false, null);
        }

        static AutoTransitionResult deferred(FanoutMode previousMode, FanoutMode targetMode) {
            return new AutoTransitionResult(previousMode, targetMode, false, true, false, null);
        }

        static AutoTransitionResult manualOverride(FanoutMode mode) {
            return new AutoTransitionResult(mode, mode, false, false, true, null);
        }
    }
}
