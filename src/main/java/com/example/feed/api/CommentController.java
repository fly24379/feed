package com.example.feed.api;

import com.example.feed.security.CurrentUser;
import com.example.feed.service.EngagementService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/comments")
public class CommentController {
    private final EngagementService engagement;
    private final CurrentUser currentUser;

    public CommentController(EngagementService engagement, CurrentUser currentUser) {
        this.engagement = engagement;
        this.currentUser = currentUser;
    }

    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long commentId) {
        engagement.deleteComment(currentUser.id(jwt), commentId);
    }
}
