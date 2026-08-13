package com.example.feed.api;

import com.example.feed.service.FeedQueryService;
import com.example.feed.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feed")
public class FeedController {
    private final FeedQueryService feed;
    private final CurrentUser currentUser;

    public FeedController(FeedQueryService feed, CurrentUser currentUser) {
        this.feed = feed;
        this.currentUser = currentUser;
    }

    @GetMapping
    public FeedQueryService.FeedPage getFeed(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return feed.getFeed(currentUser.id(jwt), cursor, size);
    }
}
