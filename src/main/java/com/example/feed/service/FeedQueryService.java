package com.example.feed.service;

import com.example.feed.domain.FeedCandidate;
import com.example.feed.domain.FeedCursor;
import com.example.feed.domain.Post;
import com.example.feed.repository.FeedInboxRepository;
import com.example.feed.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FeedQueryService {
    private final UserRepository users;
    private final FeedInboxRepository inbox;
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
                            PermissionService permissions, CursorCodec cursorCodec,
                            @Value("${feed.page.default-size:20}") int defaultSize,
                            @Value("${feed.page.max-size:100}") int maxSize,
                            @Value("${feed.page.scan-factor:3}") int scanFactor,
                            @Value("${feed.page.max-scan-rounds:10}") int maxScanRounds,
                            PostPresentationService presentation) {
        this.users = users;
        this.inbox = inbox;
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
        this(users, inbox, postReadService, permissions, cursorCodec, defaultSize, maxSize,
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
            List<FeedCandidate> candidates = inbox.findPage(viewerId, scanCursor, scanSize);
            if (candidates.isEmpty()) {
                exhausted = true;
                break;
            }
            FeedCandidate lastScanned = candidates.getLast();
            scanCursor = new FeedCursor(lastScanned.publishedAt(), lastScanned.postId());
            Map<String, Post> loaded = postReadService.findByIds(
                    candidates.stream().map(FeedCandidate::postId).toList());
            visible.addAll(permissions.filterVisible(viewerId, candidates, loaded));
            exhausted = candidates.size() < scanSize;
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

    public record FeedPage(List<Post> items, String nextCursor, boolean hasMore,
                           Map<String, PostPresentationService.SocialSummary> socialByPostId) {
        public FeedPage(List<Post> items, String nextCursor, boolean hasMore) {
            this(items, nextCursor, hasMore, Map.of());
        }
    }
}
