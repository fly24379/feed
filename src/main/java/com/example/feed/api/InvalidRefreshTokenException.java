package com.example.feed.api;

public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("刷新令牌无效、已过期或已被撤销");
    }
}
