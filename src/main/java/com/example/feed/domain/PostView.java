package com.example.feed.domain;

import java.time.Instant;
import java.util.List;

public record PostView(String id, long authorId, String content, Visibility visibility,
                       PostStatus status, Instant publishedAt, long likeCount, long commentCount,
                       boolean likedByMe, List<MediaAttachment> attachments) {
    public static PostView from(Post post, long likeCount, long commentCount,
                                boolean likedByMe, List<MediaAttachment> attachments) {
        return new PostView(post.id(), post.authorId(), post.content(), post.visibility(), post.status(),
                post.publishedAt(), likeCount, commentCount, likedByMe, attachments);
    }
}
