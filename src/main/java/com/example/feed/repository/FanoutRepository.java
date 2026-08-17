package com.example.feed.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FanoutRepository {
    private final JdbcClient jdbc;

    public FanoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int fanoutPost(String postId) {
        return fanoutPosts(List.of(postId));
    }

    public int fanoutPosts(Collection<String> postIds) {
        if (postIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                INSERT IGNORE INTO feed_inbox(owner_id, post_id, author_id, published_at)
                SELECT f.follower_id,
                       p.id, p.author_id, p.published_at
                  FROM posts p
                  JOIN follows f ON f.followee_id = p.author_id
                 WHERE p.id IN (:postIds) AND p.status = 'ACTIVE' AND p.visibility <> 'ONLY_ME'
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = p.author_id AND b.blocked_id = f.follower_id)
                           OR (b.blocked_id = p.author_id AND b.blocker_id = f.follower_id)
                   )
                   AND (p.visibility <> 'INCLUDE_LIST' OR EXISTS (
                       SELECT 1 FROM post_acl a
                        WHERE a.post_id = p.id AND a.rule_type = 'ALLOW'
                          AND a.target_user_id = f.follower_id
                   ))
                   AND (p.visibility <> 'EXCLUDE_LIST' OR NOT EXISTS (
                       SELECT 1 FROM post_acl a
                        WHERE a.post_id = p.id AND a.rule_type = 'DENY'
                          AND a.target_user_id = f.follower_id
                   ))
                """).param("postIds", postIds).update();
    }
}
