package com.example.feed.api;

public class VerificationRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public VerificationRateLimitException(long retryAfterSeconds) {
        super("验证码请求过于频繁，请稍后重试");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
