package com.example.feed.service;

import com.example.feed.domain.FanoutMode;
import com.example.feed.messaging.FanoutMessage;
import com.example.feed.repository.FanoutRepository;
import com.example.feed.repository.OutboxRepository;
import com.example.feed.repository.PostRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FanoutConsumerTest {
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final FanoutRepository fanout = mock(FanoutRepository.class);
    private final PostRepository posts = mock(PostRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final FanoutConsumer consumer = new FanoutConsumer(outbox, fanout, posts, objectMapper,
            new OutboxBackoff(Duration.ofSeconds(1), Duration.ofMinutes(1)), 8);

    @Test
    void pullPostSkipsInboxFanoutAndCompletesOutbox() throws Exception {
        ConsumerRecord<String, String> record = record(41, "pull-post");
        // The instance id is deliberately opaque, so claim matching is configured by argument shape below.
        when(outbox.claimForConsumption(org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.startsWith("consumer:"))).thenReturn(true);
        when(outbox.findById(41)).thenReturn(Optional.of(event(41, "pull-post")));
        when(posts.findDeliveryMode("pull-post")).thenReturn(Optional.of(FanoutMode.PULL));

        consumer.consume(record);

        verify(fanout, never()).fanoutPost("pull-post");
        verify(outbox).markProcessed(org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.startsWith("consumer:"));
    }

    @Test
    void pushPostStillFansOutBeforeCompletingOutbox() throws Exception {
        ConsumerRecord<String, String> record = record(42, "push-post");
        when(outbox.claimForConsumption(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.startsWith("consumer:"))).thenReturn(true);
        when(outbox.findById(42)).thenReturn(Optional.of(event(42, "push-post")));
        when(posts.findDeliveryMode("push-post")).thenReturn(Optional.of(FanoutMode.PUSH));

        consumer.consume(record);

        verify(fanout).fanoutPost("push-post");
        verify(outbox).markProcessed(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.startsWith("consumer:"));
    }

    private ConsumerRecord<String, String> record(long eventId, String postId) throws Exception {
        FanoutMessage message = new FanoutMessage(eventId, postId, 1,
                Instant.parse("2026-08-15T00:00:00Z"));
        return new ConsumerRecord<>("feed.post-published.v1", 0, eventId, postId,
                objectMapper.writeValueAsString(message));
    }

    private OutboxRepository.OutboxEvent event(long eventId, String postId) {
        return new OutboxRepository.OutboxEvent(eventId, postId, 1,
                Instant.parse("2026-08-15T00:00:00Z"));
    }
}
