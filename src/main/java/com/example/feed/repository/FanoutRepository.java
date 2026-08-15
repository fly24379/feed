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
                SELECT CASE WHEN f.user_low = p.author_id THEN f.user_high ELSE f.user_low END,
                       p.id, p.author_id, p.published_at
                  FROM posts p
                  JOIN friendships f
                    ON (f.user_low = p.author_id OR f.user_high = p.author_id)
                   AND f.status = 'ACTIVE'
                 WHERE p.id IN (:postIds) AND p.status = 'ACTIVE' AND p.visibility <> 'ONLY_ME'
                   AND NOT EXISTS (
                       SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = p.author_id AND b.blocked_id =
                                 CASE WHEN f.user_low = p.author_id THEN f.user_high ELSE f.user_low END)
                           OR (b.blocked_id = p.author_id AND b.blocker_id =
                                 CASE WHEN f.user_low = p.author_id THEN f.user_high ELSE f.user_low END)
                   )
                   AND (p.visibility <> 'INCLUDE_LIST' OR EXISTS (
                       SELECT 1 FROM post_acl a
                        WHERE a.post_id = p.id AND a.rule_type = 'ALLOW'
                          AND a.target_user_id = CASE WHEN f.user_low = p.author_id THEN f.user_high ELSE f.user_low END
                   ))
                   AND (p.visibility <> 'EXCLUDE_LIST' OR NOT EXISTS (
                       SELECT 1 FROM post_acl a
                        WHERE a.post_id = p.id AND a.rule_type = 'DENY'
                          AND a.target_user_id = CASE WHEN f.user_low = p.author_id THEN f.user_high ELSE f.user_low END
                   ))
                """).param("postIds", postIds).update();
    }
}
