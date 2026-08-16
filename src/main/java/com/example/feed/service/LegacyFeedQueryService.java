package com.example.feed.service;

import com.example.feed.domain.FeedCandidate;
import com.example.feed.domain.FeedCursor;
import com.example.feed.domain.Post;
import com.example.feed.repository.FeedInboxRepository;
import com.example.feed.repository.PullFeedRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** MySQL-only global-cursor implementation retained as the shadow-read baseline. */
@Service
public class LegacyFeedQueryService {
    private final FeedInboxRepository inbox;
    private final PullFeedRepository pull;
    private final PostReadService postReads;
    private final PermissionService permissions;
    private final int scanFactor;
    private final int maxScanRounds;

    public LegacyFeedQueryService(FeedInboxRepository inbox, PullFeedRepository pull,
                                  PostReadService postReads, PermissionService permissions,
                                  @Value("${feed.page.scan-factor:3}") int scanFactor,
                                  @Value("${feed.page.max-scan-rounds:10}") int maxScanRounds) {
        this.inbox = inbox;
        this.pull = pull;
        this.postReads = postReads;
        this.permissions = permissions;
        this.scanFactor = scanFactor;
        this.maxScanRounds = maxScanRounds;
    }

    public List<Post> readFirstPage(long viewerId, int size) {
        int scanSize = Math.max(size + 1, size * scanFactor);
        FeedCursor cursor = null;
        List<Post> visible = new ArrayList<>(size);
        for (int round = 0; round < maxScanRounds && visible.size() < size; round++) {
            List<FeedCandidate> candidates = merge(
                    inbox.findPage(viewerId, cursor, scanSize),
                    pull.findPageFromDatabase(viewerId, cursor, scanSize), scanSize);
            if (candidates.isEmpty()) {
                break;
            }
            FeedCandidate last = candidates.getLast();
            cursor = new FeedCursor(last.publishedAt(), last.postId());
            Map<String, Post> loaded = postReads.findByIds(
                    candidates.stream().map(FeedCandidate::postId).toList());
            visible.addAll(permissions.filterVisible(viewerId, candidates, loaded));
        }
        return visible.size() > size ? List.copyOf(visible.subList(0, size)) : List.copyOf(visible);
    }

    private List<FeedCandidate> merge(List<FeedCandidate> pushed, List<FeedCandidate> pulled, int limit) {
        Comparator<FeedCandidate> newestFirst = Comparator.comparing(FeedCandidate::publishedAt)
                .thenComparing(FeedCandidate::postId).reversed();
        Map<String, FeedCandidate> unique = new LinkedHashMap<>();
        Stream.concat(pushed.stream(), pulled.stream()).sorted(newestFirst)
                .forEach(candidate -> unique.putIfAbsent(candidate.postId(), candidate));
        return unique.values().stream().limit(limit).toList();
    }
}
