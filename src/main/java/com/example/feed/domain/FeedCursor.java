package com.example.feed.domain;

import java.time.Instant;

public record FeedCursor(Instant publishedAt, String postId) {
}
