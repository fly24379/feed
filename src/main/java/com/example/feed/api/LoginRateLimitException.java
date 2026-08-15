package com.example.feed.api;

public class LoginRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public LoginRateLimitException(long retryAfterSeconds) {
        super("登录尝试过于频繁，请稍后重试");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
