package com.example.feed.messaging;

import java.time.Instant;

public record FanoutMessage(long eventId, String postId, int attempt, Instant createdAt) {
}
