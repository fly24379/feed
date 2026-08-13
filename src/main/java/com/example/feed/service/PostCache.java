package com.example.feed.service;

import com.example.feed.domain.Post;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class PostCache {
    private static final Logger log = LoggerFactory.getLogger(PostCache.class);
    private static final String PREFIX = "feed:post:v1:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public PostCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                     @Value("${feed.cache.post-ttl:10m}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    public Optional<Post> get(String postId) {
        try {
            String value = redis.opsForValue().get(PREFIX + postId);
            return value == null ? Optional.empty() : Optional.of(objectMapper.readValue(value, Post.class));
        } catch (Exception exception) {
            log.debug("Redis read failed; falling back to MySQL", exception);
            return Optional.empty();
        }
    }

    public void put(Post post) {
        try {
            redis.opsForValue().set(PREFIX + post.id(), objectMapper.writeValueAsString(post), ttl);
        } catch (Exception exception) {
            log.debug("Redis write failed; continuing with MySQL", exception);
        }
    }

    public void evict(String postId) {
        try {
            redis.delete(PREFIX + postId);
        } catch (Exception exception) {
            log.debug("Redis eviction failed", exception);
        }
    }
}
