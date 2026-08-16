package com.example.feed.api;

import com.example.feed.service.FeedShadowVerifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/feed-shadow")
@PreAuthorize("hasRole('ADMIN')")
public class FeedShadowAdminController {
    private final FeedShadowVerifier shadow;

    public FeedShadowAdminController(FeedShadowVerifier shadow) {
        this.shadow = shadow;
    }

    @GetMapping("/metrics")
    public FeedShadowVerifier.Snapshot metrics() {
        return shadow.snapshot();
    }
}
