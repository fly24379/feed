package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.domain.FeedCursor;
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
}
