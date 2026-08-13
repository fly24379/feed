package com.example.feed.api;

import com.example.feed.service.OutboxAdminService;
import com.example.feed.service.OutboxMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/outbox")
@PreAuthorize("hasRole('ADMIN')")
public class OutboxAdminController {
    private final OutboxAdminService admin;
    private final OutboxMetrics metrics;

    public OutboxAdminController(OutboxAdminService admin, OutboxMetrics metrics) {
        this.admin = admin;
        this.metrics = metrics;
    }

    @GetMapping("/metrics")
    public OutboxMetrics.Snapshot metrics() {
        metrics.refresh();
        return metrics.snapshot();
    }

    @PostMapping("/{eventId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void replay(@PathVariable long eventId) {
        admin.replay(eventId);
    }
}
