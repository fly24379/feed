package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.FanoutPolicyRepository.FanoutPolicy;
import com.example.feed.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FanoutPolicyService {
    private final UserRepository users;
    private final FanoutPolicyRepository policies;

    public FanoutPolicyService(UserRepository users, FanoutPolicyRepository policies) {
        this.users = users;
        this.policies = policies;
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
    public void reset(long authorId) {
        users.requireExists(authorId);
        policies.delete(authorId);
    }
}
