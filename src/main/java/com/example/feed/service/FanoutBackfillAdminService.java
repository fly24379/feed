package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.api.NotFoundException;
import com.example.feed.domain.FanoutBackfillStatus;
import com.example.feed.repository.FanoutBackfillJobRepository;
import com.example.feed.repository.FanoutBackfillJobRepository.FanoutBackfillJob;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FanoutBackfillAdminService {
    private final FanoutBackfillJobRepository jobs;

    public FanoutBackfillAdminService(FanoutBackfillJobRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public FanoutBackfillJob get(String jobId) {
        return require(jobId);
    }

    @Transactional(readOnly = true)
    public List<FanoutBackfillJob> list(Long authorId, FanoutBackfillStatus status, Integer size) {
        int limit = size == null ? 20 : Math.max(1, Math.min(size, 100));
        return jobs.findRecent(authorId, status, limit);
    }

    @Transactional
    public FanoutBackfillJob pause(String jobId) {
        require(jobId);
        if (!jobs.pause(jobId)) {
            throw new ConflictException("只有等待中或执行中的回填任务可以暂停");
        }
        return require(jobId);
    }

    @Transactional
    public FanoutBackfillJob resume(String jobId) {
        require(jobId);
        if (!jobs.resume(jobId)) {
            throw new ConflictException("只有已暂停的回填任务可以继续");
        }
        return require(jobId);
    }

    @Transactional
    public FanoutBackfillJob retry(String jobId) {
        FanoutBackfillJob job = require(jobId);
        if (jobs.hasActiveForAuthor(job.authorId())) {
            throw new ConflictException("该作者已有进行中的回填任务");
        }
        try {
            if (!jobs.retry(jobId)) {
                throw new ConflictException("只有失败的回填任务可以重试");
            }
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("该作者已有进行中的回填任务");
        }
        return require(jobId);
    }

    @Transactional
    public FanoutBackfillJob cancel(String jobId) {
        require(jobId);
        if (!jobs.cancel(jobId)) {
            throw new ConflictException("当前状态的回填任务不能取消");
        }
        return require(jobId);
    }

    private FanoutBackfillJob require(String jobId) {
        return jobs.find(jobId).orElseThrow(() -> new NotFoundException("回填任务不存在: " + jobId));
    }
}
