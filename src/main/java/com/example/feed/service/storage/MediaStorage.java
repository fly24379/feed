package com.example.feed.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public interface MediaStorage {
    String provider();

    void put(String key, InputStream content, long sizeBytes, String contentType) throws IOException;

    InputStream read(String key) throws IOException;

    Optional<ObjectMetadata> head(String key);

    void delete(String key);

    default Optional<PresignedRequest> presignPut(String key, String contentType, Duration ttl) {
        return Optional.empty();
    }

    default Optional<PresignedRequest> presignGet(String key, String contentType,
                                                   String contentDisposition, Duration ttl) {
        return Optional.empty();
    }

    record ObjectMetadata(long sizeBytes, String contentType) {
    }

    record PresignedRequest(String url, String method, Map<String, String> headers,
                            Instant expiresAt) {
    }
}
