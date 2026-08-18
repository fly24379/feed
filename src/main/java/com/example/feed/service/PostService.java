package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.api.ConflictException;
import com.example.feed.api.NotFoundException;
import com.example.feed.domain.AclRule;
import com.example.feed.domain.FanoutMode;
import com.example.feed.domain.Post;
import com.example.feed.domain.PostStatus;
import com.example.feed.domain.Visibility;
import com.example.feed.repository.FeedInboxRepository;
import com.example.feed.repository.FanoutPolicyRepository;
import com.example.feed.repository.OutboxRepository;
import com.example.feed.repository.MediaRepository;
import com.example.feed.repository.PostRepository;
import com.example.feed.repository.RelationshipRepository;
import com.example.feed.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final FanoutPolicyRepository fanoutPolicies;
    private final AuthorTimelineCache authorTimeline;
    private final MediaRepository media;
    private final int maxAttachments;

    @Autowired
    public PostService(UserRepository users, RelationshipRepository relationships, PostRepository posts,
                       FeedInboxRepository inbox, OutboxRepository outbox, PostCache cache,
                       FanoutPolicyRepository fanoutPolicies, AuthorTimelineCache authorTimeline,
                       MediaRepository media,
                       @Value("${feed.media.max-attachments-per-post:9}") int maxAttachments) {
        this.users = users;
        this.relationships = relationships;
        this.posts = posts;
        this.inbox = inbox;
        this.outbox = outbox;
        this.cache = cache;
        this.fanoutPolicies = fanoutPolicies;
        this.authorTimeline = authorTimeline;
        this.media = media;
        this.maxAttachments = maxAttachments;
    }

    PostService(UserRepository users, RelationshipRepository relationships, PostRepository posts,
                FeedInboxRepository inbox, OutboxRepository outbox, PostCache cache) {
        this(users, relationships, posts, inbox, outbox, cache, null, null, null, 9);
    }

    PostService(UserRepository users, RelationshipRepository relationships, PostRepository posts,
                FeedInboxRepository inbox, OutboxRepository outbox, PostCache cache,
                FanoutPolicyRepository fanoutPolicies) {
        this(users, relationships, posts, inbox, outbox, cache, fanoutPolicies, null, null, 9);
    }

    @Transactional
    public Post publish(long authorId, UUID idempotencyKey, String content, Visibility visibility,
                        Set<Long> requestedTargets) {
        return publish(authorId, idempotencyKey, content, visibility, requestedTargets, Set.of());
    }

    @Transactional
    public Post publish(long authorId, UUID idempotencyKey, String content, Visibility visibility,
                        Set<Long> requestedTargets, Set<UUID> requestedMediaIds) {
        // Serialize policy lookup with manual and automatic fanout-mode transitions so a new post
        // always snapshots either the mode before or after a transition, never an in-between state.
        users.requireExistsForUpdate(authorId);
        Set<Long> targetIds = new LinkedHashSet<>(requestedTargets == null ? Set.of() : requestedTargets);
        targetIds.remove(authorId);
        Set<String> mediaIds = requestedMediaIds == null ? Set.of() : requestedMediaIds.stream()
                .map(UUID::toString).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String fingerprint = fingerprint(content, visibility, targetIds, mediaIds);
        var existing = posts.findByIdempotencyKey(authorId, idempotencyKey.toString());
        if (existing.isPresent()) {
            return requireSameRequest(existing.get(), fingerprint);
        }
        validateTargets(authorId, visibility, targetIds);

        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Post post = new Post(UUID.randomUUID().toString(), authorId, content, visibility,
                PostStatus.ACTIVE, publishedAt);
        FanoutMode deliveryMode = fanoutPolicies == null
                ? FanoutMode.PUSH : fanoutPolicies.resolveMode(authorId);
        posts.insert(post, idempotencyKey.toString(), fingerprint, deliveryMode);
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
        if (media != null) {
            media.attachToPost(authorId, post.id(), mediaIds, maxAttachments);
        }
        inbox.insertSelf(authorId, post.id(), publishedAt);
        outbox.addPostPublished(post.id());
        afterCommit(() -> {
            cache.put(post);
            if (deliveryMode == FanoutMode.PULL && authorTimeline != null) {
                authorTimeline.append(authorId, new com.example.feed.domain.FeedCandidate(post.id(), publishedAt));
            }
        });
        return post;
    }

    private Post requireSameRequest(PostRepository.IdempotentPost existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new ConflictException("Idempotency-Key 已用于不同的发布请求");
        }
        return existing.post();
    }

    private String fingerprint(String content, Visibility visibility, Set<Long> targetIds) {
        return fingerprint(content, visibility, targetIds, Set.of());
    }

    private String fingerprint(String content, Visibility visibility, Set<Long> targetIds,
                               Set<String> mediaIds) {
        try {
            String canonicalTargets = targetIds.stream().sorted().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            String canonicalMedia = mediaIds.stream().sorted().collect(java.util.stream.Collectors.joining(","));
            String canonical = content.length() + ":" + content + "|" + visibility.name()
                    + "|" + canonicalTargets.length() + ":" + canonicalTargets;
            // Keep the pre-media fingerprint stable so in-flight retries from the previous release still match.
            if (!canonicalMedia.isEmpty()) {
                canonical += "|media|" + canonicalMedia.length() + ":" + canonicalMedia;
            }
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
        afterCommit(() -> {
            cache.evict(postId);
            if (authorTimeline != null) {
                authorTimeline.evict(authorId);
            }
        });
    }

    private void validateTargets(long authorId, Visibility visibility, Set<Long> targetIds) {
        if ((visibility == Visibility.ALL_FOLLOWERS || visibility == Visibility.ALL_FRIENDS
                || visibility == Visibility.ONLY_ME) && !targetIds.isEmpty()) {
            throw new BadRequestException(visibility + " 不允许指定 targetUserIds");
        }
        if (visibility != Visibility.INCLUDE_LIST && visibility != Visibility.EXCLUDE_LIST) {
            return;
        }
        for (long targetId : targetIds) {
            users.requireExists(targetId);
            if (!relationships.isFollowingUnblocked(targetId, authorId)) {
                throw new BadRequestException("可见范围中的用户不是有效粉丝: " + targetId);
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
