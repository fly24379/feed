package com.example.feed.api;

import com.example.feed.domain.Post;
import com.example.feed.domain.Visibility;
import com.example.feed.service.PostService;
import com.example.feed.service.PermissionService;
import com.example.feed.service.PostPresentationService;
import com.example.feed.domain.PostView;
import com.example.feed.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/posts")
public class PostController {
    private final PostService posts;
    private final CurrentUser currentUser;
    private final PermissionService permissions;
    private final PostPresentationService presentation;

    public PostController(PostService posts, CurrentUser currentUser, PermissionService permissions,
                          PostPresentationService presentation) {
        this.posts = posts;
        this.currentUser = currentUser;
        this.permissions = permissions;
        this.presentation = presentation;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Post publish(@AuthenticationPrincipal Jwt jwt,
                        @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                        @Valid @RequestBody PublishPostRequest request) {
        return posts.publish(currentUser.id(jwt), idempotencyKey,
                request.content(), request.visibility(), request.targetUserIds(), request.mediaIds());
    }

    @GetMapping("/{postId}")
    public PostView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String postId) {
        long viewerId = currentUser.id(jwt);
        return presentation.view(viewerId, permissions.requireVisible(viewerId, postId));
    }

    @DeleteMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String postId) {
        posts.delete(currentUser.id(jwt), postId);
    }

    public record PublishPostRequest(
            @NotBlank @Size(max = 2000) String content,
            @NotNull Visibility visibility,
            Set<Long> targetUserIds,
            Set<UUID> mediaIds
    ) {
    }
}
