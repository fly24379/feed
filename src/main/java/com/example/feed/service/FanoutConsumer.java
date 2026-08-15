package com.example.feed.service;

import com.example.feed.messaging.FanoutMessage;
import com.example.feed.domain.FanoutMode;
import com.example.feed.repository.FanoutRepository;
import com.example.feed.repository.OutboxRepository;
import com.example.feed.repository.PostRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Component
public class FanoutConsumer {
    private final OutboxRepository outbox;
    private final FanoutRepository fanout;
    private final PostRepository posts;
    private final ObjectMapper objectMapper;
    private final OutboxBackoff backoff;
    private final int maxAttempts;
    private final Clock clock = Clock.systemUTC();
    private final String instanceId = "consumer:" + UUID.randomUUID();

    public FanoutConsumer(OutboxRepository outbox, FanoutRepository fanout, PostRepository posts,
                          ObjectMapper objectMapper,
                          OutboxBackoff backoff,
                          @Value("${feed.fanout.max-attempts:8}") int maxAttempts) {
        this.outbox = outbox;
        this.fanout = fanout;
        this.posts = posts;
        this.objectMapper = objectMapper;
        this.backoff = backoff;
        this.maxAttempts = maxAttempts;
    }

    @KafkaListener(topics = "${feed.fanout.topic}")
    @Transactional
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        FanoutMessage message = objectMapper.readValue(record.value(), FanoutMessage.class);
        String consumerId = instanceId + ":" + record.partition() + ":" + record.offset();
        if (!outbox.claimForConsumption(message.eventId(), consumerId)) {
            // Already processed, failed, replayed, or superseded by a newer dispatch.
            return;
        }
        try {
            OutboxRepository.OutboxEvent event = outbox.findById(message.eventId())
                    .orElseThrow(() -> new IllegalStateException("outbox event not found"));
            if (!event.postId().equals(message.postId())) {
                throw new IllegalArgumentException("Kafka message aggregate does not match outbox event");
            }
            FanoutMode deliveryMode = posts.findDeliveryMode(event.postId())
                    .orElseThrow(() -> new IllegalStateException("post delivery mode not found"));
            if (deliveryMode == FanoutMode.PUSH) {
                fanout.fanoutPost(event.postId());
            }
            outbox.markProcessed(event.id(), consumerId);
        } catch (RuntimeException exception) {
            outbox.scheduleRetry(message.eventId(), consumerId,
                    clock.instant().plus(backoff.forAttempt(message.attempt())),
                    exception.getClass().getSimpleName() + ": " + exception.getMessage(), maxAttempts);
        }
    }
}
