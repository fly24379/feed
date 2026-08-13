package com.example.feed.api;

import com.example.feed.domain.UserProfile;
import com.example.feed.security.CurrentUser;
import com.example.feed.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService users;
    private final CurrentUser currentUser;

    public UserController(UserService users, CurrentUser currentUser) {
        this.users = users;
        this.currentUser = currentUser;
    }

    @GetMapping("/me")
    public UserProfile me(@AuthenticationPrincipal Jwt jwt) {
        return users.get(currentUser.id(jwt));
    }

    @PatchMapping("/me")
    public UserProfile update(@AuthenticationPrincipal Jwt jwt,
                              @Valid @RequestBody UpdateProfileRequest request) {
        return users.update(currentUser.id(jwt), request.nickname(), request.bio(), request.avatarUrl());
    }

    @GetMapping("/search")
    public UserService.UserPage search(@RequestParam String q,
                                       @RequestParam(required = false) Long afterId,
                                       @RequestParam(required = false) Integer size) {
        return users.search(q, afterId, size);
    }

    @GetMapping("/{userId}")
    public UserProfile get(@PathVariable long userId) {
        return users.get(userId);
    }

    public record UpdateProfileRequest(@Size(max = 80) String nickname,
                                       @Size(max = 500) String bio,
                                       @Size(max = 500) String avatarUrl) {
    }
}
