package com.example.feed.messaging;

import com.example.feed.domain.Visibility;
import com.example.feed.repository.FeedInboxRepository;
import com.example.feed.repository.RelationshipRepository;
import com.example.feed.repository.UserRepository;
import com.example.feed.service.PostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "runKafkaIntegration", matches = "true")
@EmbeddedKafka(partitions = 1, topics = "feed.post-published.v1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3307/feed?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC",
        "spring.datasource.username=feed",
        "spring.datasource.password=feed",
        "spring.kafka.admin.fail-fast=true",
        "feed.fanout.dispatch-delay-ms=50",
        "feed.fanout.recovery-delay-ms=100",
        "feed.security.jwt.secret=integration-test-secret-with-at-least-32-bytes"
})
class KafkaFanoutIntegrationTest {
    @Autowired
    UserRepository users;
    @Autowired
    RelationshipRepository relationships;
    @Autowired
    PostService posts;
    @Autowired
    FeedInboxRepository inbox;
    @Autowired
    JdbcClient jdbc;

    @Test
    void outboxTravelsThroughKafkaAndFansOutExactlyOnce() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        long author = users.create("kafka_author_" + suffix, "Kafka Author", "ACCOUNT_DISABLED");
        long friend = users.create("kafka_friend_" + suffix, "Kafka Friend", "ACCOUNT_DISABLED");
        relationships.addFriend(author, friend);

        UUID key = UUID.randomUUID();
        var first = posts.publish(author, key, "through kafka", Visibility.ALL_FRIENDS, Set.of());
        var duplicate = posts.publish(author, key, "through kafka", Visibility.ALL_FRIENDS, Set.of());
        assertThat(duplicate.id()).isEqualTo(first.id());

        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)
                && inbox.findPage(friend, null, 10).stream().noneMatch(row -> row.postId().equals(first.id()))) {
            Thread.sleep(100);
        }

        assertThat(inbox.findPage(friend, null, 10)).filteredOn(row -> row.postId().equals(first.id()))
                .hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM posts WHERE author_id = :author AND idempotency_key = :key")
                .param("author", author).param("key", key.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM outbox_events WHERE aggregate_id = :postId")
                .param("postId", first.id()).query(String.class).single()).isEqualTo("PROCESSED");
    }
}
