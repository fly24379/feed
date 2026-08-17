package com.example.feed.service;

import com.example.feed.domain.Post;
import com.example.feed.domain.PostStatus;
import com.example.feed.domain.Visibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionServiceTest {
    private final PermissionService service = new PermissionService(null, null);
    private final Instant now = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void authorCanSeeOwnActivePostRegardlessOfVisibility() {
        Post post = post("p1", 1, Visibility.ONLY_ME, PostStatus.ACTIVE);
        assertThat(service.canView(1, post, Set.of(1L), Map.of(), Map.of())).isTrue();
    }

    @Test
    void staleInboxEntryCannotBypassCurrentFollowRelationship() {
        Post post = post("p1", 1, Visibility.ALL_FRIENDS, PostStatus.ACTIVE);
        assertThat(service.canView(2, post, Set.of(2L), Map.of(), Map.of())).isFalse();
    }

    @Test
    void currentFollowerCanViewFollowerScopedPost() {
        Post post = post("p1", 1, Visibility.ALL_FOLLOWERS, PostStatus.ACTIVE);
        assertThat(service.canView(2, post, Set.of(1L), Map.of(), Map.of())).isTrue();
    }

    @Test
    void includeAndExcludeListsAreEnforced() {
        Post included = post("include", 1, Visibility.INCLUDE_LIST, PostStatus.ACTIVE);
        Post excluded = post("exclude", 1, Visibility.EXCLUDE_LIST, PostStatus.ACTIVE);

        assertThat(service.canView(2, included, Set.of(1L), Map.of("include", Set.of(2L)), Map.of())).isTrue();
        assertThat(service.canView(3, included, Set.of(1L), Map.of("include", Set.of(2L)), Map.of())).isFalse();
        assertThat(service.canView(2, excluded, Set.of(1L), Map.of(), Map.of("exclude", Set.of(2L)))).isFalse();
        assertThat(service.canView(3, excluded, Set.of(1L), Map.of(), Map.of("exclude", Set.of(2L)))).isTrue();
    }

    @Test
    void deletedPostIsNeverVisible() {
        Post post = post("p1", 1, Visibility.ALL_FRIENDS, PostStatus.DELETED);
        assertThat(service.canView(1, post, Set.of(1L), Map.of(), Map.of())).isFalse();
    }

    private Post post(String id, long authorId, Visibility visibility, PostStatus status) {
        return new Post(id, authorId, "content", visibility, status, now);
    }
}
