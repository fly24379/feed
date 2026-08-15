package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.domain.FeedCursor;
import com.example.feed.domain.HybridFeedCursor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Component
public class CursorCodec {
    public String encode(FeedCursor cursor) {
        Instant value = cursor.publishedAt().truncatedTo(ChronoUnit.MICROS);
        long epochMicros = Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000L),
                value.getNano() / 1_000L);
        String plain = "v1:" + epochMicros + ":" + cursor.postId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    public FeedCursor decode(String encoded) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = plain.split(":", 3);
            if (parts.length != 3 || !"v1".equals(parts[0]) || parts[2].isBlank()) {
                throw new IllegalArgumentException("invalid cursor shape");
            }
            long epochMicros = Long.parseLong(parts[1]);
            Instant time = Instant.ofEpochSecond(Math.floorDiv(epochMicros, 1_000_000L),
                    Math.floorMod(epochMicros, 1_000_000L) * 1_000L);
            return new FeedCursor(time, parts[2]);
        } catch (RuntimeException exception) {
            throw new BadRequestException("cursor 无效");
        }
    }

    public String encodeHybrid(HybridFeedCursor cursor) {
        String plain = "v2:" + encodePart(cursor.inbox()) + ":" + encodePart(cursor.pull());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    public HybridFeedCursor decodeHybrid(String encoded) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            if (plain.startsWith("v1:")) {
                FeedCursor legacy = decode(encoded);
                return new HybridFeedCursor(legacy, legacy);
            }
            String[] parts = plain.split(":", 3);
            if (parts.length != 3 || !"v2".equals(parts[0])) {
                throw new IllegalArgumentException("invalid hybrid cursor shape");
            }
            return new HybridFeedCursor(decodePart(parts[1]), decodePart(parts[2]));
        } catch (RuntimeException exception) {
            if (exception instanceof BadRequestException badRequest) {
                throw badRequest;
            }
            throw new BadRequestException("cursor 无效");
        }
    }

    private String encodePart(FeedCursor cursor) {
        if (cursor == null) {
            return "-";
        }
        Instant value = cursor.publishedAt().truncatedTo(ChronoUnit.MICROS);
        long epochMicros = Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000L),
                value.getNano() / 1_000L);
        String postId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cursor.postId().getBytes(StandardCharsets.UTF_8));
        return epochMicros + "," + postId;
    }

    private FeedCursor decodePart(String part) {
        if ("-".equals(part)) {
            return null;
        }
        String[] values = part.split(",", 2);
        if (values.length != 2 || values[1].isBlank()) {
            throw new IllegalArgumentException("invalid cursor source position");
        }
        long epochMicros = Long.parseLong(values[0]);
        Instant time = Instant.ofEpochSecond(Math.floorDiv(epochMicros, 1_000_000L),
                Math.floorMod(epochMicros, 1_000_000L) * 1_000L);
        String postId = new String(Base64.getUrlDecoder().decode(values[1]), StandardCharsets.UTF_8);
        if (postId.isBlank()) {
            throw new IllegalArgumentException("blank cursor post id");
        }
        return new FeedCursor(time, postId);
    }
}
