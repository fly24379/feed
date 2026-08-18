package com.example.feed.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "runMySqlIntegration", matches = "true")
@JdbcTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3307/feed?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC",
        "spring.datasource.username=feed",
        "spring.datasource.password=feed"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FeedInboxArchiveRepository.class)
class FeedInboxArchiveRepositoryIntegrationTest {
    @Autowired
    FeedInboxArchiveRepository archive;
    @Autowired
    JdbcClient jdbc;

    @Test
    @Transactional
    void movesOnlyExpiredEntriesToTheMonthlyArchive() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String username = "archive_" + suffix;
        jdbc.sql("""
                INSERT INTO users(username, nickname, password_hash)
                VALUES (:username, 'Archive Test', 'not-used')
                """).param("username", username).update();
        long ownerId = jdbc.sql("SELECT id FROM users WHERE username = :username")
                .param("username", username).query(Long.class).single();
        Instant cutoff = Instant.now().minusSeconds(90L * 24 * 60 * 60);
        String oldPost = insertInboxEntry(ownerId, cutoff.minusSeconds(2));
        String anotherOldPost = insertInboxEntry(ownerId, cutoff.minusSeconds(1));
        String hotPost = insertInboxEntry(ownerId, cutoff.plusSeconds(1));

        assertThat(archive.archiveNextBatch(cutoff, 10)).isEqualTo(2);

        assertThat(jdbc.sql("SELECT post_id FROM feed_inbox WHERE owner_id = :ownerId")
                .param("ownerId", ownerId).query(String.class).list()).containsExactly(hotPost);
        assertThat(jdbc.sql("SELECT post_id FROM feed_inbox_archive WHERE owner_id = :ownerId")
                .param("ownerId", ownerId).query(String.class).list())
                .containsExactlyInAnyOrder(oldPost, anotherOldPost);
    }

    private String insertInboxEntry(long ownerId, Instant publishedAt) {
        String postId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO posts(id, author_id, content, visibility, status, published_at)
                VALUES (:postId, :ownerId, 'archive test', 'ALL_FOLLOWERS', 'ACTIVE', :publishedAt)
                """).param("postId", postId).param("ownerId", ownerId).param("publishedAt", publishedAt)
                .update();
        jdbc.sql("""
                INSERT INTO feed_inbox(owner_id, post_id, author_id, published_at)
                VALUES (:ownerId, :postId, :ownerId, :publishedAt)
                """).param("ownerId", ownerId).param("postId", postId).param("publishedAt", publishedAt)
                .update();
        return postId;
    }
}
