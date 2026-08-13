package com.example.feed.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FanoutJob {
    private static final Logger log = LoggerFactory.getLogger(FanoutJob.class);
    private final FanoutService service;
    private final int batchSize;

    public FanoutJob(FanoutService service, @Value("${feed.fanout.batch-size:50}") int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${feed.fanout.fixed-delay-ms:300}")
    public void run() {
        try {
            for (int i = 0; i < batchSize && service.processOne(); i++) {
                // Each event has its own transaction so multiple app instances can safely share work.
            }
        } catch (RuntimeException exception) {
            log.error("Feed fanout failed; event remains pending for retry", exception);
        }
    }
}
