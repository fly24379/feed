package com.example.feed.service;

import com.example.feed.domain.FeedCandidate;
import com.example.feed.domain.FeedCursor;
import com.example.feed.domain.Post;
import com.example.feed.repository.FeedInboxRepository;
import com.example.feed.repository.PullFeedRepository;
import com.example.feed.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class FeedQueryService {
    private final UserRepository users;
    private final FeedInboxRepository inbox;
    private final PullFeedRepository pullFeed;
    private final PostReadService postReadService;
    private final PermissionService permissions;
    private final CursorCodec cursorCodec;
    private final int defaultSize;
    private final int maxSize;
    private final int scanFactor;
    private final int maxScanRounds;
    private final PostPresentationService presentation;

    @Autowired
    public FeedQueryService(UserRepository users, FeedInboxRepository inbox, PostReadService postReadService,
                            PullFeedRepository pullFeed, PermissionService permissions, CursorCodec cursorCodec,
                            @Value("${feed.page.default-size:20}") int defaultSize,
                            @Value("${feed.page.max-size:100}") int maxSize,
                            @Value("${feed.page.scan-factor:3}") int scanFactor,
                            @Value("${feed.page.max-scan-rounds:10}") int maxScanRounds,
                            PostPresentationService presentation) {
        this.users = users;
        this.inbox = inbox;
        this.pullFeed = pullFeed;
        this.postReadService = postReadService;
        this.permissions = permissions;
        this.cursorCodec = cursorCodec;
        this.defaultSize = defaultSize;
        this.maxSize = maxSize;
        this.scanFactor = scanFactor;
        this.maxScanRounds = maxScanRounds;
        this.presentation = presentation;
    }

    FeedQueryService(UserRepository users, FeedInboxRepository inbox, PostReadService postReadService,
                     PermissionService permissions, CursorCodec cursorCodec, int defaultSize,
                     int maxSize, int scanFactor, int maxScanRounds) {
        this(users, inbox, postReadService, null, permissions, cursorCodec, defaultSize, maxSize,
                scanFactor, maxScanRounds, null);
    }

    FeedQueryService(UserRepository users, FeedInboxRepository inbox, PostReadService postReadService,
                     PullFeedRepository pullFeed, PermissionService permissions, CursorCodec cursorCodec,
                     int defaultSize, int maxSize, int scanFactor, int maxScanRounds) {
        this(users, inbox, postReadService, pullFeed, permissions, cursorCodec, defaultSize, maxSize,
                scanFactor, maxScanRounds, null);
    }

    @Transactional(readOnly = true)
    public FeedPage getFeed(long viewerId, String encodedCursor, Integer requestedSize) {
        users.requireExists(viewerId);
        int size = requestedSize == null ? defaultSize : Math.max(1, Math.min(requestedSize, maxSize));
        FeedCursor requestedCursor = encodedCursor == null || encodedCursor.isBlank()
                ? null : cursorCodec.decode(encodedCursor);
        FeedCursor scanCursor = requestedCursor;
        int scanSize = Math.max(size + 1, size * scanFactor);
        List<Post> visible = new ArrayList<>(size + 1);
        boolean exhausted = false;

        for (int round = 0; round < maxScanRounds && visible.size() <= size && !exhausted; round++) {
            CandidateBatch batch = findCandidates(viewerId, scanCursor, scanSize);
            List<FeedCandidate> candidates = batch.items();
            if (candidates.isEmpty()) {
                exhausted = true;
                break;
            }
            FeedCandidate lastScanned = candidates.getLast();
            scanCursor = new FeedCursor(lastScanned.publishedAt(), lastScanned.postId());
            Map<String, Post> loaded = postReadService.findByIds(
                    candidates.stream().map(FeedCandidate::postId).toList());
            visible.addAll(permissions.filterVisible(viewerId, candidates, loaded));
            exhausted = batch.exhausted();
        }

        List<Post> items = visible.size() > size ? List.copyOf(visible.subList(0, size)) : List.copyOf(visible);
        boolean hasMore = visible.size() > size || !exhausted;
        String nextCursor = null;
        if (!items.isEmpty() && hasMore) {
            Post last = items.getLast();
            nextCursor = cursorCodec.encode(new FeedCursor(last.publishedAt(), last.id()));
        } else if (items.isEmpty() && hasMore && scanCursor != null) {
            // All scanned rows were filtered. Advancing prevents an endless empty page.
            nextCursor = cursorCodec.encode(scanCursor);
        }
        Map<String, PostPresentationService.SocialSummary> social = presentation == null
                ? Map.of() : presentation.summaries(viewerId, items);
        return new FeedPage(items, nextCursor, hasMore, social);
    }

    private CandidateBatch findCandidates(long viewerId, FeedCursor cursor, int limit) {
        List<FeedCandidate> pushed = inbox.findPage(viewerId, cursor, limit);
        List<FeedCandidate> pulled = pullFeed == null
                ? List.of() : pullFeed.findPage(viewerId, cursor, limit);
        Map<String, FeedCandidate> unique = new LinkedHashMap<>();
        Comparator<FeedCandidate> newestFirst = Comparator.comparing(FeedCandidate::publishedAt)
                .thenComparing(FeedCandidate::postId).reversed();
        Stream.concat(pushed.stream(), pulled.stream()).sorted(newestFirst)
                .forEach(candidate -> unique.putIfAbsent(candidate.postId(), candidate));
        List<FeedCandidate> merged = unique.values().stream().limit(limit).toList();
        boolean exhausted = merged.size() < limit && pushed.size() < limit && pulled.size() < limit;
        return new CandidateBatch(merged, exhausted);
    }

    private record CandidateBatch(List<FeedCandidate> items, boolean exhausted) {
    }

    public record FeedPage(List<Post> items, String nextCursor, boolean hasMore,
                           Map<String, PostPresentationService.SocialSummary> socialByPostId) {
        public FeedPage(List<Post> items, String nextCursor, boolean hasMore) {
            this(items, nextCursor, hasMore, Map.of());
        }
    }
}
