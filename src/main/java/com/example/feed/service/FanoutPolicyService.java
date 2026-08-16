package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.FanoutPolicyRepository.FanoutPolicy;
import com.example.feed.repository.FanoutRepository;
import com.example.feed.repository.PostRepository;
import com.example.feed.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FanoutPolicyService {
    private final UserRepository users;
    private final FanoutPolicyRepository policies;
    private final PostRepository posts;
    private final FanoutRepository fanout;
    private final AuthorTimelineCache authorTimeline;

    @Autowired
    public FanoutPolicyService(UserRepository users, FanoutPolicyRepository policies,
                               PostRepository posts, FanoutRepository fanout,
                               AuthorTimelineCache authorTimeline) {
        this.users = users;
        this.policies = policies;
        this.posts = posts;
        this.fanout = fanout;
        this.authorTimeline = authorTimeline;
    }

    FanoutPolicyService(UserRepository users, FanoutPolicyRepository policies,
                        PostRepository posts, FanoutRepository fanout) {
        this(users, policies, posts, fanout, null);
    }

    @Transactional(readOnly = true)
    public FanoutPolicy get(long authorId) {
        users.requireExists(authorId);
        return policies.find(authorId).orElseGet(() -> FanoutPolicy.defaultPush(authorId));
    }

    @Transactional
    public FanoutPolicy set(long authorId, FanoutMode mode, String reason) {
        users.requireExists(authorId);
        policies.upsert(authorId, mode, reason);
        return policies.find(authorId)
                .orElseThrow(() -> new IllegalStateException("扩散策略写入后无法读取"));
    }

    @Transactional
    public FanoutSwitchResult switchMode(long authorId, FanoutMode mode, String reason, int historyLimit) {
        users.requireExists(authorId);
        if (historyLimit < 0 || historyLimit > 5_000) {
            throw new IllegalArgumentException("历史回填数量必须在 0 到 5000 之间");
        }
        FanoutMode previousMode = policies.resolveMode(authorId);
        policies.upsert(authorId, mode, reason);
        var postIds = posts.findRecentPostIdsForModeChange(authorId, mode, historyLimit);
        int historyUpdated = posts.updateDeliveryMode(postIds, mode);
        int inboxRowsInserted = mode == FanoutMode.PUSH ? fanout.fanoutPosts(postIds) : 0;
        if (authorTimeline != null) {
            authorTimeline.evict(authorId);
        }
        FanoutPolicy policy = policies.find(authorId)
                .orElseThrow(() -> new IllegalStateException("扩散策略写入后无法读取"));
        return new FanoutSwitchResult(previousMode, policy, historyUpdated, inboxRowsInserted);
    }

    @Transactional
    public void reset(long authorId) {
        users.requireExists(authorId);
        policies.delete(authorId);
    }

    public record FanoutSwitchResult(FanoutMode previousMode, FanoutPolicy policy,
                                     int historyUpdated, int inboxRowsInserted) {
    }
}
