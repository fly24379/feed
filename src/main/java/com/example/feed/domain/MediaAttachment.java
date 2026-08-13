package com.example.feed.domain;

import java.time.Instant;

public record MediaAttachment(String id, String mediaType, String contentType,
                              String originalFilename, long sizeBytes, String url, Instant createdAt) {
}
