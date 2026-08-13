package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.domain.Post;
import com.example.feed.domain.PostStatus;
import com.example.feed.domain.Visibility;
import com.example.feed.repository.FeedInboxRepository;
import com.example.feed.repository.OutboxRepository;
import com.example.feed.repository.PostRepository;
import com.example.feed.repository.RelationshipRepository;
import com.example.feed.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceIdempotencyTest {
    private final UserRepository users = mock(UserRepository.class);
    private final RelationshipRepository relationships = mock(RelationshipRepository.class);
    private final PostRepository posts = mock(PostRepository.class);
    private final FeedInboxRepository inbox = mock(FeedInboxRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final PostCache cache = mock(PostCache.class);
    private final PostService service = new PostService(users, relationships, posts, inbox, outbox, cache);
    private final UUID key = UUID.fromString("58d474a8-00a7-4c56-9959-6f1b0a775462");

    @Test
    void sameKeyAndPayloadReturnsOriginalWithoutSideEffects() {
        Post original = new Post("post-1", 7, "same", Visibility.ALL_FRIENDS,
                PostStatus.ACTIVE, Instant.parse("2026-08-13T00:00:00Z"));
        String fingerprint = fingerprintFor("same");
        when(posts.findByIdempotencyKey(7, key.toString()))
                .thenReturn(Optional.of(new PostRepository.IdempotentPost(original, fingerprint)));

        assertThat(service.publish(7, key, "same", Visibility.ALL_FRIENDS, Set.of()))
                .isEqualTo(original);
        verify(posts, never()).insert(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
        verify(outbox, never()).addPostPublished(anyString());
    }

    @Test
    void sameKeyWithDifferentPayloadReturnsConflict() {
        Post original = new Post("post-1", 7, "original", Visibility.ALL_FRIENDS,
                PostStatus.ACTIVE, Instant.parse("2026-08-13T00:00:00Z"));
        when(posts.findByIdempotencyKey(7, key.toString()))
                .thenReturn(Optional.of(new PostRepository.IdempotentPost(original, fingerprintFor("original"))));

        assertThatThrownBy(() -> service.publish(7, key, "different", Visibility.ALL_FRIENDS, Set.of()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void mediaIdsParticipateInFingerprintWithoutChangingLegacyEmptyFingerprint() {
        String legacy = fingerprintFor("same");
        String withMedia = fingerprintFor("same", Set.of("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

        assertThat(withMedia).isNotEqualTo(legacy);
        assertThat(fingerprintFor("same", Set.of())).isEqualTo(legacy);
    }

    private String fingerprintFor(String content) {
        try {
            var method = PostService.class.getDeclaredMethod("fingerprint", String.class, Visibility.class, Set.class);
            method.setAccessible(true);
            return (String) method.invoke(service, content, Visibility.ALL_FRIENDS, Set.of());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private String fingerprintFor(String content, Set<String> mediaIds) {
        try {
            var method = PostService.class.getDeclaredMethod("fingerprint", String.class,
                    Visibility.class, Set.class, Set.class);
            method.setAccessible(true);
            return (String) method.invoke(service, content, Visibility.ALL_FRIENDS, Set.of(), mediaIds);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
