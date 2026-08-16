package com.example.feed.domain;

public enum FanoutBackfillStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
