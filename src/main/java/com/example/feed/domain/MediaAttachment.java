package com.example.feed.domain;

import java.time.Instant;

public record MediaAttachment(String id, String mediaType, String contentType,
                              String originalFilename, long sizeBytes, String url,
                              String previewUrl, String previewStatus, Instant createdAt) {

    public MediaAttachment(String id, String mediaType, String contentType,
                           String originalFilename, long sizeBytes, String url, Instant createdAt) {
        this(id, mediaType, contentType, originalFilename, sizeBytes, url, null, "PENDING", createdAt);
    }
}
