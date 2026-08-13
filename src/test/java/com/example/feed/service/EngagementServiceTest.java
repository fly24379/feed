package com.example.feed.service;

import com.example.feed.domain.Post;
import com.example.feed.domain.PostComment;
import com.example.feed.domain.PostStatus;
import com.example.feed.domain.UserProfile;
import com.example.feed.domain.Visibility;
import com.example.feed.repository.EngagementRepository;
import com.example.feed.repository.NotificationRepository;
import com.example.feed.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngagementServiceTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final EngagementRepository engagement = mock(EngagementRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EngagementService service = new EngagementService(permissions, engagement, notifications, users);
    private final Post post = new Post("post-1", 1, "hello", Visibility.ALL_FRIENDS,
            PostStatus.ACTIVE, Instant.parse("2026-08-13T00:00:00Z"));

    @Test
    void firstLikeCreatesNotificationAndReturnsCount() {
        when(permissions.requireVisible(2, "post-1")).thenReturn(post);
        when(engagement.addLike("post-1", 2)).thenReturn(true);
        when(users.requireProfile(2)).thenReturn(new UserProfile(2, "bob", "Bob", "", null));
        when(engagement.findStats(java.util.List.of("post-1"), 2))
                .thenReturn(Map.of("post-1", new EngagementRepository.EngagementStats(3, 0, true)));

        assertThat(service.like(2, "post-1").likeCount()).isEqualTo(3);
        verify(notifications).add(1, "POST_LIKED", 2L, "POST", "post-1", "Bob 点赞了你的动态");
    }

    @Test
    void repeatedLikeDoesNotCreateAnotherNotification() {
        when(permissions.requireVisible(2, "post-1")).thenReturn(post);
        when(engagement.addLike("post-1", 2)).thenReturn(false);
        when(engagement.findStats(java.util.List.of("post-1"), 2))
                .thenReturn(Map.of("post-1", new EngagementRepository.EngagementStats(1, 0, true)));

        service.like(2, "post-1");

        verify(notifications, never()).add(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void commentNotifiesPostAuthor() {
        UserProfile bob = new UserProfile(2, "bob", "Bob", "", null);
        PostComment comment = new PostComment(7, "post-1", bob, "nice",
                Instant.parse("2026-08-13T00:01:00Z"), Instant.parse("2026-08-13T00:01:00Z"));
        when(permissions.requireVisible(2, "post-1")).thenReturn(post);
        when(engagement.addComment("post-1", 2, "nice")).thenReturn(comment);

        assertThat(service.comment(2, "post-1", " nice ")).isEqualTo(comment);
        verify(notifications).add(1, "POST_COMMENTED", 2L, "POST", "post-1", "Bob 评论了你的动态");
    }
}
