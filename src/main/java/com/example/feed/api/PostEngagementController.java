package com.example.feed.api;

import com.example.feed.domain.PostComment;
import com.example.feed.security.CurrentUser;
import com.example.feed.service.EngagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts/{postId}")
public class PostEngagementController {
    private final EngagementService engagement;
    private final CurrentUser currentUser;

    public PostEngagementController(EngagementService engagement, CurrentUser currentUser) {
        this.engagement = engagement;
        this.currentUser = currentUser;
    }

    @PutMapping("/like")
    public EngagementService.LikeResult like(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable String postId) {
        return engagement.like(currentUser.id(jwt), postId);
    }

    @DeleteMapping("/like")
    public EngagementService.LikeResult unlike(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable String postId) {
        return engagement.unlike(currentUser.id(jwt), postId);
    }

    @PostMapping("/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public PostComment comment(@AuthenticationPrincipal Jwt jwt, @PathVariable String postId,
                               @Valid @RequestBody CommentRequest request) {
        return engagement.comment(currentUser.id(jwt), postId, request.content());
    }

    @GetMapping("/comments")
    public EngagementService.CommentPage comments(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable String postId,
                                                   @RequestParam(required = false) Long afterId,
                                                   @RequestParam(required = false) Integer size) {
        return engagement.listComments(currentUser.id(jwt), postId, afterId, size);
    }

    public record CommentRequest(@NotBlank @Size(max = 1000) String content) {
    }
}
