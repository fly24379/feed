package com.example.feed.api;

import com.example.feed.domain.FanoutBackfillStatus;
import com.example.feed.repository.FanoutBackfillJobRepository.FanoutBackfillJob;
import com.example.feed.service.FanoutBackfillAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/fanout-backfills")
@PreAuthorize("hasRole('ADMIN')")
public class FanoutBackfillAdminController {
    private final FanoutBackfillAdminService backfills;

    public FanoutBackfillAdminController(FanoutBackfillAdminService backfills) {
        this.backfills = backfills;
    }

    @GetMapping
    public List<FanoutBackfillJob> list(
            @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) FanoutBackfillStatus status,
            @RequestParam(required = false) Integer size) {
        return backfills.list(authorId, status, size);
    }

    @GetMapping("/{jobId}")
    public FanoutBackfillJob get(@PathVariable String jobId) {
        return backfills.get(jobId);
    }

    @PostMapping("/{jobId}/pause")
    public FanoutBackfillJob pause(@PathVariable String jobId) {
        return backfills.pause(jobId);
    }

    @PostMapping("/{jobId}/resume")
    public FanoutBackfillJob resume(@PathVariable String jobId) {
        return backfills.resume(jobId);
    }

    @PostMapping("/{jobId}/retry")
    public FanoutBackfillJob retry(@PathVariable String jobId) {
        return backfills.retry(jobId);
    }

    @PostMapping("/{jobId}/cancel")
    @ResponseStatus(HttpStatus.OK)
    public FanoutBackfillJob cancel(@PathVariable String jobId) {
        return backfills.cancel(jobId);
    }
}
