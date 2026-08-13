package com.example.feed.api;

import com.example.feed.service.RelationshipService;
import com.example.feed.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/relationships")
public class RelationshipController {
    private final RelationshipService relationships;
    private final CurrentUser currentUser;

    public RelationshipController(RelationshipService relationships, CurrentUser currentUser) {
        this.relationships = relationships;
        this.currentUser = currentUser;
    }

    @PutMapping("/friends/{friendId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addFriend(@AuthenticationPrincipal Jwt jwt, @PathVariable long friendId) {
        relationships.addFriend(currentUser.id(jwt), friendId);
    }

    @DeleteMapping("/friends/{friendId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFriend(@AuthenticationPrincipal Jwt jwt, @PathVariable long friendId) {
        relationships.removeFriend(currentUser.id(jwt), friendId);
    }

    @PutMapping("/blocks/{blockedId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@AuthenticationPrincipal Jwt jwt, @PathVariable long blockedId) {
        relationships.block(currentUser.id(jwt), blockedId);
    }

    @DeleteMapping("/blocks/{blockedId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable long blockedId) {
        relationships.unblock(currentUser.id(jwt), blockedId);
    }
}
