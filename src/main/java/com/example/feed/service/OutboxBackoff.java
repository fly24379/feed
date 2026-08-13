package com.example.feed.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OutboxBackoff {
    private final Duration initial;
    private final Duration maximum;

    public OutboxBackoff(@Value("${feed.fanout.initial-backoff:1s}") Duration initial,
                         @Value("${feed.fanout.max-backoff:15m}") Duration maximum) {
        this.initial = initial;
        this.maximum = maximum;
    }

    public Duration forAttempt(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 30));
        long multiplier = 1L << exponent;
        try {
            Duration calculated = initial.multipliedBy(multiplier);
            return calculated.compareTo(maximum) > 0 ? maximum : calculated;
        } catch (ArithmeticException exception) {
            return maximum;
        }
    }
}
