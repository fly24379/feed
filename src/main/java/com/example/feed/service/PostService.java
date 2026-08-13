package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.api.ConflictException;
import com.example.feed.api.NotFoundException;
import com.example.feed.domain.AclRule;
import com.example.feed.domain.Post;
import com.example.feed.domain.PostStatus;
import com.example.feed.domain.Visibility;
import com.example.feed.repository.FeedInboxRepository;
import com.example.feed.repository.OutboxRepository;
import com.example.feed.repository.PostRepository;
import com.example.feed.repository.RelationshipRepository;
import com.example.feed.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class PostService {
    private final UserRepository users;
    private final RelationshipRepository relationships;
    private final PostRepository posts;
    private final FeedInboxRepository inbox;
    private final OutboxRepository outbox;
    private final PostCache cache;

    public PostService(UserRepository users, RelationshipRepository relationships, PostRepository posts,
                       FeedInboxRepository inbox, OutboxRepository outbox, PostCache cache) {
        this.users = users;
        this.relationships = relationships;
        this.posts = posts;
        this.inbox = inbox;
        this.outbox = outbox;
        this.cache = cache;
    }

    @Transactional
    public Post publish(long authorId, UUID idempotencyKey, String content, Visibility visibility,
                        Set<Long> requestedTargets) {
        users.requireExists(authorId);
        Set<Long> targetIds = new LinkedHashSet<>(requestedTargets == null ? Set.of() : requestedTargets);
        targetIds.remove(authorId);
        String fingerprint = fingerprint(content, visibility, targetIds);
        var existing = posts.findByIdempotencyKey(authorId, idempotencyKey.toString());
        if (existing.isPresent()) {
            return requireSameRequest(existing.get(), fingerprint);
        }
        validateTargets(authorId, visibility, targetIds);

        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Post post = new Post(UUID.randomUUID().toString(), authorId, content, visibility,
                PostStatus.ACTIVE, publishedAt);
        posts.insert(post, idempotencyKey.toString(), fingerprint);
        var stored = posts.findByIdempotencyKeyForUpdate(authorId, idempotencyKey.toString())
                .orElseThrow(() -> new IllegalStateException("幂等发布写入后无法读取"));
        if (!stored.post().id().equals(post.id())) {
            return requireSameRequest(stored, fingerprint);
        }
        if (visibility == Visibility.INCLUDE_LIST) {
            posts.insertAcl(post.id(), targetIds, AclRule.ALLOW);
        } else if (visibility == Visibility.EXCLUDE_LIST) {
            posts.insertAcl(post.id(), targetIds, AclRule.DENY);
        }
        inbox.insertSelf(authorId, post.id(), publishedAt);
        outbox.addPostPublished(post.id());
        afterCommit(() -> cache.put(post));
        return post;
    }

    private Post requireSameRequest(PostRepository.IdempotentPost existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new ConflictException("Idempotency-Key 已用于不同的发布请求");
        }
        return existing.post();
    }

    private String fingerprint(String content, Visibility visibility, Set<Long> targetIds) {
        try {
            String canonicalTargets = targetIds.stream().sorted().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            String canonical = content.length() + ":" + content + "|" + visibility.name()
                    + "|" + canonicalTargets.length() + ":" + canonicalTargets;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Transactional
    public void delete(long authorId, String postId) {
        if (posts.markDeleted(postId, authorId) == 0) {
            throw new NotFoundException("动态不存在，或不属于当前用户");
        }
        afterCommit(() -> cache.evict(postId));
    }

    private void validateTargets(long authorId, Visibility visibility, Set<Long> targetIds) {
        if ((visibility == Visibility.ALL_FRIENDS || visibility == Visibility.ONLY_ME) && !targetIds.isEmpty()) {
            throw new BadRequestException(visibility + " 不允许指定 targetUserIds");
        }
        if (visibility != Visibility.INCLUDE_LIST && visibility != Visibility.EXCLUDE_LIST) {
            return;
        }
        for (long targetId : targetIds) {
            users.requireExists(targetId);
            if (!relationships.isActiveUnblockedFriend(authorId, targetId)) {
                throw new BadRequestException("可见范围中的用户不是有效好友: " + targetId);
            }
        }
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
