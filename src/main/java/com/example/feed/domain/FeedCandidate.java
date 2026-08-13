package com.example.feed.domain;

import java.time.Instant;

public record FeedCandidate(String postId, Instant publishedAt) {
}
