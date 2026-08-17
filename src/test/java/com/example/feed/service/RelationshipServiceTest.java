package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.domain.FriendRequest;
import com.example.feed.domain.FriendRequestStatus;
import com.example.feed.domain.UserProfile;
import com.example.feed.repository.FriendRequestRepository;
import com.example.feed.repository.NotificationRepository;
import com.example.feed.repository.RelationshipRepository;
import com.example.feed.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationshipServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final RelationshipRepository relationships = mock(RelationshipRepository.class);
    private final FriendRequestRepository requests = mock(FriendRequestRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final RelationshipService service = new RelationshipService(users, relationships, requests, notifications);

    @Test
    void sendsRequestAndNotifiesRecipient() {
        FriendRequest request = request(FriendRequestStatus.PENDING);
        when(requests.findPendingBetween(1, 2)).thenReturn(Optional.empty());
        when(requests.createOrReopen(1, 2)).thenReturn(request);

        assertThat(service.sendRequest(1, 2)).isEqualTo(request);

        verify(notifications).add(2, "FRIEND_REQUEST", 1L, "FRIEND_REQUEST", "10",
                "Alice 请求添加你为好友");
    }

    @Test
    void duplicatePendingRequestIsRejectedWithoutSideEffects() {
        when(requests.findPendingBetween(1, 2)).thenReturn(Optional.of(request(FriendRequestStatus.PENDING)));

        assertThatThrownBy(() -> service.sendRequest(1, 2)).isInstanceOf(ConflictException.class);

        verify(requests, never()).createOrReopen(1, 2);
    }

    @Test
    void recipientAcceptsRequestAndCreatesFriendship() {
        FriendRequest pending = request(FriendRequestStatus.PENDING);
        FriendRequest accepted = request(FriendRequestStatus.ACCEPTED);
        when(requests.find(10)).thenReturn(Optional.of(pending)).thenReturn(Optional.of(accepted));
        when(requests.transition(10, 2, true, FriendRequestStatus.ACCEPTED)).thenReturn(true);

        assertThat(service.accept(2, 10).status()).isEqualTo(FriendRequestStatus.ACCEPTED);

        verify(relationships).addFriend(1, 2);
        verify(notifications).add(1, "FRIEND_REQUEST_ACCEPTED", 2L,
                "FRIEND_REQUEST", "10", "Bob 已接受你的好友申请");
        verify(relationships).backfillRecentPushPosts(1, 2, 200);
        verify(relationships).backfillRecentPushPosts(2, 1, 200);
    }

    @Test
    void followingIsIdempotentBackfillsPushHistoryAndNotifiesOnce() {
        UserProfile alice = new UserProfile(1, "alice", "Alice", "", null);
        UserProfile bob = new UserProfile(2, "bob", "Bob", "", null);
        when(users.requireProfile(1)).thenReturn(alice);
        when(users.requireProfile(2)).thenReturn(bob);
        when(relationships.follow(1, 2)).thenReturn(true);
        when(relationships.backfillRecentPushPosts(1, 2, 200)).thenReturn(3);
        when(relationships.findFollowStats(1, 2))
                .thenReturn(new RelationshipRepository.FollowStats(4, 9, true, false));

        var result = service.follow(1, 2);

        assertThat(result.followedByMe()).isTrue();
        assertThat(result.backfilledPosts()).isEqualTo(3);
        verify(notifications).add(2, "NEW_FOLLOWER", 1L, "USER", "1", "Alice 关注了你");
    }

    @Test
    void repeatedFollowDoesNotBackfillOrNotifyAgain() {
        UserProfile bob = new UserProfile(2, "bob", "Bob", "", null);
        when(users.requireProfile(2)).thenReturn(bob);
        when(relationships.follow(1, 2)).thenReturn(false);
        when(relationships.findFollowStats(1, 2))
                .thenReturn(new RelationshipRepository.FollowStats(1, 1, true, false));

        service.follow(1, 2);

        verify(relationships, never()).backfillRecentPushPosts(1, 2, 200);
        verify(notifications, never()).add(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void followerListUsesStableUserIdCursorAndBoundedPage() {
        var highest = new UserProfile(9, "nine", "Nine", "", null);
        var middle = new UserProfile(7, "seven", "Seven", "", null);
        var extra = new UserProfile(5, "five", "Five", "", null);
        when(relationships.findFollowers(1, Long.MAX_VALUE, 3))
                .thenReturn(List.of(highest, middle, extra));

        var page = service.listFollowers(1, null, 2);

        assertThat(page.items()).containsExactly(highest, middle);
        assertThat(page.nextBeforeUserId()).isEqualTo(7);
        assertThat(page.hasMore()).isTrue();
    }

    private FriendRequest request(FriendRequestStatus status) {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        return new FriendRequest(10, new UserProfile(1, "alice", "Alice", "", null),
                new UserProfile(2, "bob", "Bob", "", null), status, now, now);
    }
}
