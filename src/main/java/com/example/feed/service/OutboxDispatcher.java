package com.example.feed.service;

import com.example.feed.messaging.FanoutMessage;
import com.example.feed.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxDispatcher {
    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final OutboxBackoff backoff;
    private final String topic;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration sendTimeout;
    private final Clock clock = Clock.systemUTC();
    private final String instanceId = "dispatcher:" + UUID.randomUUID();

    public OutboxDispatcher(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                            ObjectMapper objectMapper, OutboxBackoff backoff,
                            @Value("${feed.fanout.topic}") String topic,
                            @Value("${feed.fanout.batch-size:50}") int batchSize,
                            @Value("${feed.fanout.max-attempts:8}") int maxAttempts,
                            @Value("${feed.fanout.send-timeout:10s}") Duration sendTimeout) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.backoff = backoff;
        this.topic = topic;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sendTimeout = sendTimeout;
    }

    @Scheduled(fixedDelayString = "${feed.fanout.dispatch-delay-ms:300}")
    public void dispatch() {
        List<OutboxRepository.OutboxEvent> events = outbox.findDuePending(batchSize);
        for (OutboxRepository.OutboxEvent event : events) {
            dispatchOne(event);
        }
    }

    private void dispatchOne(OutboxRepository.OutboxEvent event) {
        if (!outbox.markDispatching(event.id(), instanceId)) {
            return;
        }
        int attempt = event.attempts() + 1;
        try {
            String payload = objectMapper.writeValueAsString(
                    new FanoutMessage(event.id(), event.postId(), attempt, event.createdAt()));
            kafka.send(topic, event.postId(), payload).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            outbox.markDispatched(event.id(), instanceId);
        } catch (Exception exception) {
            outbox.scheduleRetry(event.id(), instanceId, clock.instant().plus(backoff.forAttempt(attempt)),
                    rootMessage(exception), maxAttempts);
        }
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }
}
