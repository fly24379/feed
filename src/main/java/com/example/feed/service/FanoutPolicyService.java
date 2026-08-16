package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.domain.FanoutMode;
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
}
