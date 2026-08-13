package com.example.feed.domain;

import java.time.Instant;

public record PostComment(long id, String postId, UserProfile author, String content,
                          Instant createdAt, Instant updatedAt) {
}
