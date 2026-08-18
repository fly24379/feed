package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.FanoutPolicySource;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.RelationshipRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class FanoutAutoPolicyJob {
    private static final Logger log = LoggerFactory.getLogger(FanoutAutoPolicyJob.class);

    private final RelationshipRepository relationships;
    private final FanoutPolicyRepository policies;
    private final FanoutPolicyService policyService;
    private final boolean enabled;
    private final long pullThreshold;
    private final long pushThreshold;
    private final long historyLimit;
    private final int batchSize;
    private final int maxBatches;
    private final Counter promoted;
    private final Counter reverted;
    private final AtomicLong lastEvaluated = new AtomicLong();

    public FanoutAutoPolicyJob(RelationshipRepository relationships, FanoutPolicyRepository policies,
                               FanoutPolicyService policyService,
                               MeterRegistry registry,
                               @Value("${feed.fanout.auto-policy.enabled:true}") boolean enabled,
                               @Value("${feed.fanout.auto-policy.pull-threshold:10000}") long pullThreshold,
                               @Value("${feed.fanout.auto-policy.push-threshold:8000}") long pushThreshold,
                               @Value("${feed.fanout.auto-policy.history-limit:50000}") long historyLimit,
                               @Value("${feed.fanout.auto-policy.batch-size:500}") int batchSize,
                               @Value("${feed.fanout.auto-policy.max-batches:20}") int maxBatches) {
        if (pushThreshold > pullThreshold || historyLimit < 0) {
            throw new IllegalArgumentException("自动扩散策略阈值或历史回填数量无效");
        }
        this.relationships = relationships;
        this.policies = policies;
        this.policyService = policyService;
        this.enabled = enabled;
        this.pullThreshold = pullThreshold;
        this.pushThreshold = pushThreshold;
        this.historyLimit = historyLimit;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.promoted = Counter.builder("feed.fanout.auto.promoted").register(registry);
        this.reverted = Counter.builder("feed.fanout.auto.reverted").register(registry);
    }

    @Scheduled(fixedDelayString = "${feed.fanout.auto-policy.delay-ms:60000}",
            initialDelayString = "${feed.fanout.auto-policy.initial-delay-ms:15000}")
    public void scheduledRefresh() {
        if (enabled) {
            refresh();
        }
    }

    public Snapshot refresh() {
        long afterUserId = 0;
        long evaluated = 0;
        long promotedCount = 0;
        long revertedCount = 0;
        try {
            for (int batch = 0; batch < maxBatches; batch++) {
                var counts = relationships.findConnectionCountsAfter(afterUserId, batchSize);
                if (counts.isEmpty()) {
                    break;
                }
                for (var count : counts) {
                    evaluated++;
                    var current = policies.find(count.userId());
                    if (current.isPresent() && current.get().source() == FanoutPolicySource.MANUAL) {
                        continue;
                    }
                    FanoutMode desiredMode = null;
                    if (count.followerCount() >= pullThreshold) {
                        desiredMode = FanoutMode.PULL;
                    } else if (current.isPresent() && current.get().source() == FanoutPolicySource.AUTO) {
                        if (count.followerCount() <= pushThreshold) {
                            desiredMode = FanoutMode.PUSH;
                        } else {
                            desiredMode = FanoutMode.PULL;
                        }
                    }
                    if (desiredMode == null) {
                        continue;
                    }
                    var result = policyService.reconcileAuto(count.userId(), count.followerCount(),
                            desiredMode, historyLimit);
                    if (result.transitioned() && desiredMode == FanoutMode.PULL) {
                        promoted.increment();
                        promotedCount++;
                    } else if (result.transitioned() && desiredMode == FanoutMode.PUSH) {
                        reverted.increment();
                        revertedCount++;
                    }
                }
                afterUserId = counts.getLast().userId();
                if (counts.size() < batchSize) {
                    break;
                }
            }
            lastEvaluated.set(evaluated);
        } catch (RuntimeException exception) {
            log.warn("Automatic fanout policy evaluation failed", exception);
        }
        return new Snapshot(enabled, pullThreshold, pushThreshold, evaluated,
                promotedCount, revertedCount, lastEvaluated.get());
    }

    public Snapshot snapshot() {
        return new Snapshot(enabled, pullThreshold, pushThreshold, 0, 0, 0, lastEvaluated.get());
    }

    public record Snapshot(boolean enabled, long pullThreshold, long pushThreshold,
                           long evaluatedThisRun, long promotedThisRun, long revertedThisRun,
                           long lastEvaluated) {
    }
}
