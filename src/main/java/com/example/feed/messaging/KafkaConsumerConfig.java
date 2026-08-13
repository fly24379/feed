package com.example.feed.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        // Business retries are driven by MySQL Outbox available_at, not by blocking a Kafka partition.
        return new DefaultErrorHandler(new FixedBackOff(0, 0));
    }
}
