package com.example.feed.domain;

import java.time.Instant;

public record FriendRequest(long id, UserProfile requester, UserProfile recipient,
                            FriendRequestStatus status, Instant createdAt, Instant updatedAt) {
}
