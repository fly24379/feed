package com.example.feed.service;

import com.example.feed.domain.FanoutBackfillStatus;
import com.example.feed.domain.FanoutMode;
import com.example.feed.repository.FanoutBackfillJobRepository;
import com.example.feed.repository.FanoutRepository;
import com.example.feed.repository.PostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Service
public class FanoutBackfillBatchService {
    private final FanoutBackfillJobRepository jobs;
    private final PostRepository posts;
    private final FanoutRepository fanout;
    private final AuthorTimelineCache authorTimeline;

    public FanoutBackfillBatchService(FanoutBackfillJobRepository jobs, PostRepository posts,
                                      FanoutRepository fanout, AuthorTimelineCache authorTimeline) {
        this.jobs = jobs;
        this.posts = posts;
        this.fanout = fanout;
        this.authorTimeline = authorTimeline;
    }

    @Transactional
    public BatchResult processBatch(String jobId, String processorId, int configuredBatchSize) {
        var job = jobs.findForUpdate(jobId)
                .orElseThrow(() -> new IllegalStateException("回填任务不存在: " + jobId));
        if (job.status() != FanoutBackfillStatus.RUNNING
                || !Objects.equals(job.processorId(), processorId)) {
            return new BatchResult(0, 0, false, true);
        }
        long remaining = job.totalPosts() - job.processedPosts();
        if (remaining <= 0) {
            jobs.completeWithoutMoreCandidates(jobId, processorId);
            return new BatchResult(0, 0, true, false);
        }
        int batchSize = (int) Math.min(configuredBatchSize, remaining);
        var candidates = posts.findPostBatchForModeChange(
                job.authorId(), job.targetMode(), job.lastPublishedAt(), job.lastPostId(), batchSize);
        if (candidates.isEmpty()) {
            jobs.completeWithoutMoreCandidates(jobId, processorId);
            afterCommit(() -> authorTimeline.evict(job.authorId()));
            return new BatchResult(0, 0, true, false);
        }

        var postIds = candidates.stream().map(PostRepository.ModeChangeCandidate::postId).toList();
        int updated = posts.updateDeliveryMode(postIds, job.targetMode());
        if (updated != postIds.size()) {
            throw new IllegalStateException("批次中的动态状态已发生变化，预期更新 "
                    + postIds.size() + " 条，实际更新 " + updated + " 条");
        }
        int inboxRows = job.targetMode() == FanoutMode.PUSH ? fanout.fanoutPosts(postIds) : 0;
        var last = candidates.get(candidates.size() - 1);
        boolean completed = job.processedPosts() + candidates.size() >= job.totalPosts();
        jobs.completeBatch(jobId, processorId, candidates.size(), inboxRows,
                last.publishedAt(), last.postId(), completed);
        afterCommit(() -> authorTimeline.evict(job.authorId()));
        return new BatchResult(candidates.size(), inboxRows, completed, false);
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    public record BatchResult(int processedPosts, int inboxRowsInserted,
                              boolean completed, boolean ignored) {
    }
}
