package com.example.feed.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxBackoffTest {
    private final OutboxBackoff backoff = new OutboxBackoff(Duration.ofSeconds(1), Duration.ofMinutes(15));

    @Test
    void growsExponentiallyByAttempt() {
        assertThat(backoff.forAttempt(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.forAttempt(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.forAttempt(5)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    void capsAtConfiguredMaximum() {
        assertThat(backoff.forAttempt(20)).isEqualTo(Duration.ofMinutes(15));
    }
}
