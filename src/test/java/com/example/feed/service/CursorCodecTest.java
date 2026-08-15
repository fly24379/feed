package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.domain.FeedCursor;
import com.example.feed.domain.HybridFeedCursor;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {
    private final CursorCodec codec = new CursorCodec();

    @Test
    void roundTripsAtMysqlMicrosecondPrecision() {
        FeedCursor cursor = new FeedCursor(Instant.parse("2026-08-13T12:34:56.123456Z"), "post-42");

        assertThat(codec.decode(codec.encode(cursor))).isEqualTo(cursor);
    }

    @Test
    void rejectsMalformedCursor() {
        assertThatThrownBy(() -> codec.decode("not-a-cursor"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("cursor 无效");
    }

    @Test
    void roundTripsIndependentHybridSourcePositions() {
        HybridFeedCursor cursor = new HybridFeedCursor(
                new FeedCursor(Instant.parse("2026-08-13T12:34:56.123456Z"), "push:42"),
                new FeedCursor(Instant.parse("2026-08-12T01:02:03.654321Z"), "pull/17"));

        assertThat(codec.decodeHybrid(codec.encodeHybrid(cursor))).isEqualTo(cursor);
    }

    @Test
    void upgradesLegacyCursorToBothSourcePositions() {
        FeedCursor legacy = new FeedCursor(Instant.parse("2026-08-13T12:34:56.123456Z"), "post-42");

        assertThat(codec.decodeHybrid(codec.encode(legacy)))
                .isEqualTo(new HybridFeedCursor(legacy, legacy));
    }
}
