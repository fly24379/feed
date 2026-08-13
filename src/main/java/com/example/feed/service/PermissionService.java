package com.example.feed.service;

import com.example.feed.domain.AclRule;
import com.example.feed.domain.FeedCandidate;
import com.example.feed.domain.Post;
import com.example.feed.domain.PostStatus;
import com.example.feed.repository.PostRepository;
import com.example.feed.repository.RelationshipRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PermissionService {
    private final RelationshipRepository relationships;
    private final PostRepository posts;

    public PermissionService(RelationshipRepository relationships, PostRepository posts) {
        this.relationships = relationships;
        this.posts = posts;
    }

    public List<Post> filterVisible(long viewerId, List<FeedCandidate> candidates, Map<String, Post> loaded) {
        Set<Long> authorIds = new LinkedHashSet<>();
        candidates.stream().map(candidate -> loaded.get(candidate.postId())).filter(Objects::nonNull)
                .map(Post::authorId).forEach(authorIds::add);
        Set<Long> accessibleAuthors = relationships.findAccessibleAuthors(viewerId, authorIds);
        Collection<String> postIds = candidates.stream().map(FeedCandidate::postId).toList();
        Map<String, Set<Long>> allow = posts.findAclTargets(postIds, AclRule.ALLOW);
        Map<String, Set<Long>> deny = posts.findAclTargets(postIds, AclRule.DENY);

        return candidates.stream()
                .map(candidate -> loaded.get(candidate.postId()))
                .filter(Objects::nonNull)
                .filter(post -> canView(viewerId, post, accessibleAuthors, allow, deny))
                .toList();
    }

    boolean canView(long viewerId, Post post, Set<Long> accessibleAuthors,
                    Map<String, Set<Long>> allow, Map<String, Set<Long>> deny) {
        if (post.status() != PostStatus.ACTIVE) {
            return false;
        }
        if (post.authorId() == viewerId) {
            return true;
        }
        if (!accessibleAuthors.contains(post.authorId())) {
            return false;
        }
        return switch (post.visibility()) {
            case ALL_FRIENDS -> true;
            case ONLY_ME -> false;
            case INCLUDE_LIST -> allow.getOrDefault(post.id(), Set.of()).contains(viewerId);
            case EXCLUDE_LIST -> !deny.getOrDefault(post.id(), Set.of()).contains(viewerId);
        };
    }
}
