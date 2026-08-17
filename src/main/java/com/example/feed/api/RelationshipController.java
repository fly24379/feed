package com.example.feed.api;

import com.example.feed.service.RelationshipService;
import com.example.feed.domain.FriendRequest;
import com.example.feed.domain.FriendRequestStatus;
import com.example.feed.domain.UserProfile;
import com.example.feed.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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

import java.util.List;

@RestController
@RequestMapping("/api/relationships")
public class RelationshipController {
    private final RelationshipService relationships;
    private final CurrentUser currentUser;

    public RelationshipController(RelationshipService relationships, CurrentUser currentUser) {
        this.relationships = relationships;
        this.currentUser = currentUser;
    }

    @PostMapping("/friend-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public FriendRequest sendRequest(@AuthenticationPrincipal Jwt jwt,
                                     @Valid @RequestBody SendFriendRequest request) {
        return relationships.sendRequest(currentUser.id(jwt), request.recipientId());
    }

    @GetMapping("/friend-requests")
    public RelationshipService.RequestPage listRequests(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "INCOMING") RequestBox box,
            @RequestParam(defaultValue = "PENDING") FriendRequestStatus status,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer size) {
        return relationships.listRequests(currentUser.id(jwt), box == RequestBox.INCOMING,
                status, beforeId, size);
    }

    @PostMapping("/friend-requests/{requestId}/accept")
    public FriendRequest accept(@AuthenticationPrincipal Jwt jwt, @PathVariable long requestId) {
        return relationships.accept(currentUser.id(jwt), requestId);
    }

    @PostMapping("/friend-requests/{requestId}/reject")
    public FriendRequest reject(@AuthenticationPrincipal Jwt jwt, @PathVariable long requestId) {
        return relationships.reject(currentUser.id(jwt), requestId);
    }

    @DeleteMapping("/friend-requests/{requestId}")
    public FriendRequest withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable long requestId) {
        return relationships.withdraw(currentUser.id(jwt), requestId);
    }

    @GetMapping("/friends")
    public List<UserProfile> listFriends(@AuthenticationPrincipal Jwt jwt) {
        return relationships.listFriends(currentUser.id(jwt));
    }

    @GetMapping("/following")
    public RelationshipService.FollowPage listFollowing(@AuthenticationPrincipal Jwt jwt,
                                                         @RequestParam(required = false) Long beforeUserId,
                                                         @RequestParam(required = false) Integer size) {
        return relationships.listFollowing(currentUser.id(jwt), beforeUserId, size);
    }

    @GetMapping("/followers")
    public RelationshipService.FollowPage listFollowers(@AuthenticationPrincipal Jwt jwt,
                                                         @RequestParam(required = false) Long beforeUserId,
                                                         @RequestParam(required = false) Integer size) {
        return relationships.listFollowers(currentUser.id(jwt), beforeUserId, size);
    }

    @GetMapping("/follows/{userId}")
    public RelationshipService.FollowState followState(@AuthenticationPrincipal Jwt jwt,
                                                        @PathVariable long userId) {
        return relationships.getFollowState(currentUser.id(jwt), userId);
    }

    @PutMapping("/follows/{userId}")
    public RelationshipService.FollowState follow(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable long userId) {
        return relationships.follow(currentUser.id(jwt), userId);
    }

    @DeleteMapping("/follows/{userId}")
    public RelationshipService.FollowState unfollow(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable long userId) {
        return relationships.unfollow(currentUser.id(jwt), userId);
    }

    @GetMapping("/blocks")
    public List<UserProfile> listBlocked(@AuthenticationPrincipal Jwt jwt) {
        return relationships.listBlocked(currentUser.id(jwt));
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

    public record SendFriendRequest(@Positive long recipientId) {
    }

    public enum RequestBox {
        INCOMING,
        OUTGOING
    }
}
