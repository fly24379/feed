package com.example.feed.service;

import com.example.feed.domain.FeedCandidate;
import com.example.feed.domain.FeedCursor;
import com.example.feed.domain.HybridFeedCursor;
import com.example.feed.domain.Post;
import com.example.feed.repository.FeedInboxRepository;
import com.example.feed.repository.PullFeedRepository;
import com.example.feed.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

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
    private final FeedShadowVerifier shadow;

    @Autowired
    public FeedQueryService(UserRepository users, FeedInboxRepository inbox, PostReadService postReadService,
                            PullFeedRepository pullFeed, PermissionService permissions, CursorCodec cursorCodec,
                            @Value("${feed.page.default-size:20}") int defaultSize,
                            @Value("${feed.page.max-size:100}") int maxSize,
                            @Value("${feed.page.scan-factor:3}") int scanFactor,
                            @Value("${feed.page.max-scan-rounds:10}") int maxScanRounds,
                            PostPresentationService presentation, FeedShadowVerifier shadow) {
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
        this.shadow = shadow;
    }

    FeedQueryService(UserRepository users, FeedInboxRepository inbox, PostReadService postReadService,
                     PermissionService permissions, CursorCodec cursorCodec, int defaultSize,
                     int maxSize, int scanFactor, int maxScanRounds) {
        this(users, inbox, postReadService, null, permissions, cursorCodec, defaultSize, maxSize,
                scanFactor, maxScanRounds, null, null);
    }

    FeedQueryService(UserRepository users, FeedInboxRepository inbox, PostReadService postReadService,
                     PullFeedRepository pullFeed, PermissionService permissions, CursorCodec cursorCodec,
                     int defaultSize, int maxSize, int scanFactor, int maxScanRounds) {
        this(users, inbox, postReadService, pullFeed, permissions, cursorCodec, defaultSize, maxSize,
                scanFactor, maxScanRounds, null, null);
    }

    @Transactional(readOnly = true)
    public FeedPage getFeed(long viewerId, String encodedCursor, Integer requestedSize) {
        users.requireExists(viewerId);
        int size = requestedSize == null ? defaultSize : Math.max(1, Math.min(requestedSize, maxSize));
        HybridFeedCursor requestedCursor = encodedCursor == null || encodedCursor.isBlank()
                ? HybridFeedCursor.start() : cursorCodec.decodeHybrid(encodedCursor);
        int scanSize = Math.max(size + 1, size * scanFactor);
        MergedCandidateScanner scanner = new MergedCandidateScanner(
                new SourceScanner(requestedCursor.inbox(), scanSize,
                        (cursor, limit) -> inbox.findPage(viewerId, cursor, limit)),
                new SourceScanner(requestedCursor.pull(), scanSize,
                        (cursor, limit) -> pullFeed == null
                                ? List.of() : pullFeed.findPage(viewerId, cursor, limit)));
        List<Post> visible = new ArrayList<>(size);
        HybridFeedCursor safeCursor = requestedCursor;
        boolean foundExtraVisible = false;

        scan:
        for (int round = 0; round < maxScanRounds; round++) {
            List<PositionedCandidate> positioned = scanner.nextBatch(scanSize);
            if (positioned.isEmpty()) {
                break;
            }
            List<FeedCandidate> candidates = positioned.stream().map(PositionedCandidate::candidate).toList();
            Map<String, Post> loaded = postReadService.findByIds(
                    candidates.stream().map(FeedCandidate::postId).toList());
            Map<String, Post> visibleById = new HashMap<>();
            permissions.filterVisible(viewerId, candidates, loaded)
                    .forEach(post -> visibleById.put(post.id(), post));
            for (PositionedCandidate current : positioned) {
                Post post = visibleById.get(current.candidate().postId());
                if (post != null && visible.size() == size) {
                    foundExtraVisible = true;
                    break scan;
                }
                safeCursor = current.cursorAfter();
                if (post != null) {
                    visible.add(post);
                }
            }
        }

        List<Post> items = List.copyOf(visible);
        boolean hasMore = foundExtraVisible || !scanner.exhausted();
        String nextCursor = hasMore && !safeCursor.equals(requestedCursor)
                ? cursorCodec.encodeHybrid(safeCursor) : null;
        Map<String, PostPresentationService.SocialSummary> social = presentation == null
                ? Map.of() : presentation.summaries(viewerId, items);
        FeedPage page = new FeedPage(items, nextCursor, hasMore, social);
        if (shadow != null && (encodedCursor == null || encodedCursor.isBlank())) {
            shadow.compareFirstPage(viewerId, size, items);
        }
        return page;
    }

    private static FeedCursor positionOf(FeedCandidate candidate) {
        return new FeedCursor(candidate.publishedAt(), candidate.postId());
    }

    private static int compareNewestFirst(FeedCandidate left, FeedCandidate right) {
        int time = right.publishedAt().compareTo(left.publishedAt());
        return time != 0 ? time : right.postId().compareTo(left.postId());
    }

    private static final class SourceScanner {
        private final int pageSize;
        private final BiFunction<FeedCursor, Integer, List<FeedCandidate>> loader;
        private FeedCursor position;
        private List<FeedCandidate> buffer = List.of();
        private int index;
        private boolean exhausted;

        private SourceScanner(FeedCursor position, int pageSize,
                              BiFunction<FeedCursor, Integer, List<FeedCandidate>> loader) {
            this.position = position;
            this.pageSize = pageSize;
            this.loader = loader;
        }

        private FeedCandidate peek() {
            if (index >= buffer.size() && !exhausted) {
                buffer = loader.apply(position, pageSize);
                index = 0;
                exhausted = buffer.size() < pageSize;
            }
            return index < buffer.size() ? buffer.get(index) : null;
        }

        private FeedCandidate pop() {
            FeedCandidate candidate = peek();
            if (candidate != null) {
                index++;
                position = positionOf(candidate);
            }
            return candidate;
        }
    }

    private static final class MergedCandidateScanner {
        private final SourceScanner inbox;
        private final SourceScanner pull;
        private final Set<String> emittedPostIds = new HashSet<>();

        private MergedCandidateScanner(SourceScanner inbox, SourceScanner pull) {
            this.inbox = inbox;
            this.pull = pull;
        }

        private List<PositionedCandidate> nextBatch(int limit) {
            List<PositionedCandidate> result = new ArrayList<>(limit);
            while (result.size() < limit) {
                FeedCandidate pushed = inbox.peek();
                FeedCandidate pulled = pull.peek();
                if (pushed == null && pulled == null) {
                    break;
                }
                FeedCandidate next;
                if (pushed != null && pulled != null && compareNewestFirst(pushed, pulled) == 0) {
                    next = inbox.pop();
                    pull.pop();
                } else if (pulled == null || pushed != null && compareNewestFirst(pushed, pulled) < 0) {
                    next = inbox.pop();
                } else {
                    next = pull.pop();
                }
                if (emittedPostIds.add(next.postId())) {
                    result.add(new PositionedCandidate(next,
                            new HybridFeedCursor(inbox.position, pull.position)));
                }
            }
            return result;
        }

        private boolean exhausted() {
            return inbox.peek() == null && pull.peek() == null;
        }
    }

    private record PositionedCandidate(FeedCandidate candidate, HybridFeedCursor cursorAfter) {
    }

    public record FeedPage(List<Post> items, String nextCursor, boolean hasMore,
                           Map<String, PostPresentationService.SocialSummary> socialByPostId) {
        public FeedPage(List<Post> items, String nextCursor, boolean hasMore) {
            this(items, nextCursor, hasMore, Map.of());
        }
    }
}
