package com.example.feed.domain;

/**
 * Independent exclusive positions for the materialized Inbox and read-fanout sources.
 */
public record HybridFeedCursor(FeedCursor inbox, FeedCursor pull) {
    public static HybridFeedCursor start() {
        return new HybridFeedCursor(null, null);
    }
}
