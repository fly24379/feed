package com.example.feed.service;

import com.example.feed.domain.FeedCandidate;
import com.example.feed.domain.FeedCursor;
import com.example.feed.domain.Post;
import com.example.feed.domain.PostStatus;
import com.example.feed.domain.Visibility;
import com.example.feed.repository.FeedInboxRepository;
import com.example.feed.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedQueryServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final FeedInboxRepository inbox = mock(FeedInboxRepository.class);
    private final PostReadService postReads = mock(PostReadService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final CursorCodec codec = new CursorCodec();
    private final FeedQueryService service = new FeedQueryService(
            users, inbox, postReads, permissions, codec, 20, 100, 3, 10);

    @Test
    void nextCursorUsesLastReturnedRowNotLastScannedRow() {
        FeedCandidate newestFiltered = candidate("p3", "2026-08-13T00:00:03Z");
        FeedCandidate returned = candidate("p2", "2026-08-13T00:00:02Z");
        FeedCandidate prefetched = candidate("p1", "2026-08-13T00:00:01Z");
        Post p2 = post(returned);
        Post p1 = post(prefetched);
        when(inbox.findPage(1, null, 3)).thenReturn(List.of(newestFiltered, returned, prefetched));
        when(postReads.findByIds(anyList())).thenReturn(Map.of("p2", p2, "p1", p1));
        when(permissions.filterVisible(eq(1L), anyList(), any())).thenReturn(List.of(p2, p1));

        FeedQueryService.FeedPage page = service.getFeed(1, null, 1);

        assertThat(page.items()).containsExactly(p2);
        assertThat(page.hasMore()).isTrue();
        assertThat(codec.decode(page.nextCursor()))
                .isEqualTo(new FeedCursor(returned.publishedAt(), returned.postId()));
    }

    @Test
    void emptyFilteredPageAdvancesScanCursor() {
        FeedCandidate filtered = candidate("hidden", "2026-08-13T00:00:03Z");
        when(inbox.findPage(1, null, 3)).thenReturn(List.of(filtered, candidate("hidden2", "2026-08-13T00:00:02Z"),
                candidate("hidden1", "2026-08-13T00:00:01Z")));
        when(inbox.findPage(eq(1L), any(FeedCursor.class), eq(3))).thenReturn(List.of());
        when(postReads.findByIds(anyList())).thenReturn(Map.of());
        when(permissions.filterVisible(eq(1L), anyList(), any())).thenReturn(List.of());

        FeedQueryService.FeedPage page = service.getFeed(1, null, 1);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        verify(inbox).findPage(eq(1L), any(FeedCursor.class), eq(3));
    }

    private FeedCandidate candidate(String id, String time) {
        return new FeedCandidate(id, Instant.parse(time));
    }

    private Post post(FeedCandidate candidate) {
        return new Post(candidate.postId(), 2, "content", Visibility.ALL_FRIENDS,
                PostStatus.ACTIVE, candidate.publishedAt());
    }
}
