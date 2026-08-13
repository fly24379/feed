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
    }

    private FriendRequest request(FriendRequestStatus status) {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        return new FriendRequest(10, new UserProfile(1, "alice", "Alice", "", null),
                new UserProfile(2, "bob", "Bob", "", null), status, now, now);
    }
}
