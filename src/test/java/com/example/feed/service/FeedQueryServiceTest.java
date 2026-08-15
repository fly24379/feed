package com.example.feed.service;

import com.example.feed.domain.FeedCandidate;
import com.example.feed.domain.FeedCursor;
import com.example.feed.domain.HybridFeedCursor;
import com.example.feed.domain.Post;
import com.example.feed.domain.PostStatus;
import com.example.feed.domain.Visibility;
import com.example.feed.repository.FeedInboxRepository;
import com.example.feed.repository.PullFeedRepository;
import com.example.feed.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedQueryServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final FeedInboxRepository inbox = mock(FeedInboxRepository.class);
    private final PullFeedRepository pullFeed = mock(PullFeedRepository.class);
    private final PostReadService postReads = mock(PostReadService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final CursorCodec codec = new CursorCodec();
    private final Map<String, Post> posts = new LinkedHashMap<>();
    private FeedQueryService service;

    @BeforeEach
    void setUp() {
        service = new FeedQueryService(users, inbox, postReads, pullFeed, permissions,
                codec, 20, 100, 3, 10);
        when(postReads.findByIds(anyList())).thenAnswer(invocation -> {
            Map<String, Post> result = new LinkedHashMap<>();
            for (String id : invocation.<List<String>>getArgument(0)) {
                if (posts.containsKey(id)) {
                    result.put(id, posts.get(id));
                }
            }
            return result;
        });
        allowAllExcept(Set.of());
    }

    @Test
    void compositeCursorPaginatesUnevenSourcesWithoutDuplicatesOrGaps() {
        List<FeedCandidate> pushed = candidates("push-5@05", "push-2@02");
        List<FeedCandidate> pulled = candidates("pull-4@04", "pull-3@03", "pull-1@01");
        stubSourcePages(pushed, pulled);

        FeedQueryService.FeedPage first = service.getFeed(1, null, 2);
        HybridFeedCursor firstCursor = codec.decodeHybrid(first.nextCursor());
        FeedQueryService.FeedPage second = service.getFeed(1, first.nextCursor(), 2);
        FeedQueryService.FeedPage third = service.getFeed(1, second.nextCursor(), 2);

        assertThat(ids(first)).containsExactly("push-5", "pull-4");
        assertThat(firstCursor.inbox()).isEqualTo(position("push-5@05"));
        assertThat(firstCursor.pull()).isEqualTo(position("pull-4@04"));
        assertThat(ids(second)).containsExactly("pull-3", "push-2");
        assertThat(ids(third)).containsExactly("pull-1");
        assertThat(third.hasMore()).isFalse();
        assertThat(third.nextCursor()).isNull();
        assertThat(java.util.stream.Stream.of(first, second, third)
                .flatMap(page -> page.items().stream()).map(Post::id).toList())
                .containsExactly("push-5", "pull-4", "pull-3", "push-2", "pull-1")
                .doesNotHaveDuplicates();
    }

    @Test
    void overlappingBackfillRowsAreReturnedOnlyOnce() {
        FeedCandidate duplicate = candidate("shared-2@02");
        stubSourcePages(List.of(candidate("push-4@04"), duplicate),
                List.of(candidate("pull-3@03"), duplicate, candidate("pull-1@01")));

        FeedQueryService.FeedPage page = service.getFeed(1, null, 10);

        assertThat(ids(page)).containsExactly("push-4", "pull-3", "shared-2", "pull-1");
        assertThat(ids(page)).doesNotHaveDuplicates();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void permissionFilteredPullRowsAdvanceOnlyTheirOwnCursorAndDoNotRepeat() {
        stubSourcePages(candidates("push-3@03", "push-1@01"),
                candidates("hidden-4@04", "pull-2@02"));
        allowAllExcept(Set.of("hidden-4"));

        FeedQueryService.FeedPage first = service.getFeed(1, null, 1);
        HybridFeedCursor cursor = codec.decodeHybrid(first.nextCursor());
        FeedQueryService.FeedPage second = service.getFeed(1, first.nextCursor(), 2);

        assertThat(ids(first)).containsExactly("push-3");
        assertThat(cursor.pull()).isEqualTo(position("hidden-4@04"));
        assertThat(cursor.inbox()).isEqualTo(position("push-3@03"));
        assertThat(ids(second)).containsExactly("pull-2", "push-1");
        assertThat(ids(second)).doesNotContain("hidden-4", "push-3");
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void legacyCursorContinuesBothSourcesFromTheOldGlobalBoundary() {
        stubSourcePages(candidates("push-3@03", "push-1@01"),
                candidates("pull-4@04", "pull-2@02"));
        String legacy = codec.encode(position("legacy-boundary@03"));

        FeedQueryService.FeedPage page = service.getFeed(1, legacy, 10);

        assertThat(ids(page)).containsExactly("pull-2", "push-1");
    }

    private void stubSourcePages(List<FeedCandidate> pushed, List<FeedCandidate> pulled) {
        pushed.forEach(candidate -> posts.put(candidate.postId(), post(candidate)));
        pulled.forEach(candidate -> posts.put(candidate.postId(), post(candidate)));
        when(inbox.findPage(eq(1L), any(), anyInt())).thenAnswer(invocation -> pageAfter(
                pushed, invocation.getArgument(1), invocation.getArgument(2)));
        when(pullFeed.findPage(eq(1L), any(), anyInt())).thenAnswer(invocation -> pageAfter(
                pulled, invocation.getArgument(1), invocation.getArgument(2)));
    }

    private List<FeedCandidate> pageAfter(List<FeedCandidate> source, FeedCursor cursor, int limit) {
        return source.stream().filter(candidate -> cursor == null || olderThan(candidate, cursor))
                .limit(limit).toList();
    }

    private boolean olderThan(FeedCandidate candidate, FeedCursor cursor) {
        return candidate.publishedAt().isBefore(cursor.publishedAt())
                || candidate.publishedAt().equals(cursor.publishedAt())
                && candidate.postId().compareTo(cursor.postId()) < 0;
    }

    private void allowAllExcept(Set<String> hidden) {
        when(permissions.filterVisible(eq(1L), anyList(), any())).thenAnswer(invocation -> {
            List<FeedCandidate> candidates = invocation.getArgument(1);
            Map<String, Post> loaded = invocation.getArgument(2);
            return candidates.stream().filter(candidate -> !hidden.contains(candidate.postId()))
                    .map(candidate -> loaded.get(candidate.postId())).filter(java.util.Objects::nonNull).toList();
        });
    }

    private List<String> ids(FeedQueryService.FeedPage page) {
        return page.items().stream().map(Post::id).toList();
    }

    private List<FeedCandidate> candidates(String... specs) {
        return java.util.Arrays.stream(specs).map(this::candidate).toList();
    }

    private FeedCandidate candidate(String spec) {
        String[] parts = spec.split("@", 2);
        return new FeedCandidate(parts[0], Instant.parse("2026-08-13T00:00:" + parts[1] + "Z"));
    }

    private FeedCursor position(String spec) {
        FeedCandidate candidate = candidate(spec);
        return new FeedCursor(candidate.publishedAt(), candidate.postId());
    }

    private Post post(FeedCandidate candidate) {
        return new Post(candidate.postId(), 2, "content", Visibility.ALL_FRIENDS,
                PostStatus.ACTIVE, candidate.publishedAt());
    }
}
