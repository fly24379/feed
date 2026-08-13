package com.example.feed.service;

import com.example.feed.domain.Post;
import com.example.feed.repository.PostRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PostReadService {
    private final PostRepository posts;
    private final PostCache cache;

    public PostReadService(PostRepository posts, PostCache cache) {
        this.posts = posts;
        this.cache = cache;
    }

    public Map<String, Post> findByIds(Collection<String> postIds) {
        Map<String, Post> result = new HashMap<>();
        List<String> misses = new ArrayList<>();
        for (String postId : postIds) {
            cache.get(postId).ifPresentOrElse(post -> result.put(postId, post), () -> misses.add(postId));
        }
        Map<String, Post> loaded = posts.findByIds(misses);
        loaded.values().forEach(cache::put);
        result.putAll(loaded);
        return result;
    }
}
