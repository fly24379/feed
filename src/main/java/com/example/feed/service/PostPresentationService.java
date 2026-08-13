package com.example.feed.service;

import com.example.feed.domain.MediaAttachment;
import com.example.feed.domain.Post;
import com.example.feed.domain.PostView;
import com.example.feed.repository.EngagementRepository;
import com.example.feed.repository.MediaRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PostPresentationService {
    private final EngagementRepository engagement;
    private final MediaRepository media;

    public PostPresentationService(EngagementRepository engagement, MediaRepository media) {
        this.engagement = engagement;
        this.media = media;
    }

    public Map<String, SocialSummary> summaries(long viewerId, Collection<Post> posts) {
        List<String> ids = posts.stream().map(Post::id).toList();
        var stats = engagement.findStats(ids, viewerId);
        var attachments = media.findByPosts(ids);
        Map<String, SocialSummary> result = new LinkedHashMap<>();
        for (Post post : posts) {
            var postStats = stats.getOrDefault(post.id(), new EngagementRepository.EngagementStats(0, 0, false));
            result.put(post.id(), new SocialSummary(postStats.likeCount(), postStats.commentCount(),
                    postStats.likedByMe(), List.copyOf(attachments.getOrDefault(post.id(), List.of()))));
        }
        return result;
    }

    public PostView view(long viewerId, Post post) {
        SocialSummary summary = summaries(viewerId, List.of(post)).get(post.id());
        return PostView.from(post, summary.likeCount(), summary.commentCount(),
                summary.likedByMe(), summary.attachments());
    }

    public record SocialSummary(long likeCount, long commentCount, boolean likedByMe,
                                List<MediaAttachment> attachments) {
    }
}
