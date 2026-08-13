package com.example.feed.domain;

import java.time.Instant;

public record Post(
        String id,
        long authorId,
        String content,
        Visibility visibility,
        PostStatus status,
        Instant publishedAt
) {
}
