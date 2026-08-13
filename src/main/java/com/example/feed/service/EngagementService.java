package com.example.feed.service;

import com.example.feed.api.NotFoundException;
import com.example.feed.domain.Post;
import com.example.feed.domain.PostComment;
import com.example.feed.repository.EngagementRepository;
import com.example.feed.repository.NotificationRepository;
import com.example.feed.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EngagementService {
    private final PermissionService permissions;
    private final EngagementRepository engagement;
    private final NotificationRepository notifications;
    private final UserRepository users;

    public EngagementService(PermissionService permissions, EngagementRepository engagement,
                             NotificationRepository notifications, UserRepository users) {
        this.permissions = permissions;
        this.engagement = engagement;
        this.notifications = notifications;
        this.users = users;
    }

    @Transactional
    public LikeResult like(long userId, String postId) {
        Post post = permissions.requireVisible(userId, postId);
        boolean created = engagement.addLike(postId, userId);
        if (created) {
            String nickname = users.requireProfile(userId).nickname();
            notifications.add(post.authorId(), "POST_LIKED", userId, "POST", postId,
                    nickname + " 点赞了你的动态");
        }
        return likeResult(postId, userId);
    }

    @Transactional
    public LikeResult unlike(long userId, String postId) {
        permissions.requireVisible(userId, postId);
        engagement.removeLike(postId, userId);
        return likeResult(postId, userId);
    }

    @Transactional
    public PostComment comment(long userId, String postId, String content) {
        Post post = permissions.requireVisible(userId, postId);
        PostComment comment = engagement.addComment(postId, userId, content.strip());
        notifications.add(post.authorId(), "POST_COMMENTED", userId, "POST", postId,
                comment.author().nickname() + " 评论了你的动态");
        return comment;
    }

    @Transactional(readOnly = true)
    public CommentPage listComments(long userId, String postId, Long afterId, Integer requestedSize) {
        permissions.requireVisible(userId, postId);
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 100));
        List<PostComment> loaded = engagement.findComments(postId,
                afterId == null ? 0 : Math.max(0, afterId), size + 1);
        boolean hasMore = loaded.size() > size;
        List<PostComment> items = hasMore ? List.copyOf(loaded.subList(0, size)) : List.copyOf(loaded);
        Long nextAfterId = hasMore ? items.getLast().id() : null;
        return new CommentPage(items, nextAfterId, hasMore);
    }

    @Transactional
    public void deleteComment(long userId, long commentId) {
        if (!engagement.deleteComment(commentId, userId)) {
            throw new NotFoundException("评论不存在或无权删除: " + commentId);
        }
    }

    private LikeResult likeResult(String postId, long userId) {
        var stats = engagement.findStats(List.of(postId), userId)
                .getOrDefault(postId, new EngagementRepository.EngagementStats(0, 0, false));
        return new LikeResult(postId, stats.likeCount(), stats.likedByMe());
    }

    public record LikeResult(String postId, long likeCount, boolean likedByMe) {
    }

    public record CommentPage(List<PostComment> items, Long nextAfterId, boolean hasMore) {
    }
}
