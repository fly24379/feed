package com.example.feed.service;

import com.example.feed.repository.FanoutRepository;
import com.example.feed.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FanoutService {
    private final OutboxRepository outbox;
    private final FanoutRepository fanout;

    public FanoutService(OutboxRepository outbox, FanoutRepository fanout) {
        this.outbox = outbox;
        this.fanout = fanout;
    }

    @Transactional
    public boolean processOne() {
        return outbox.lockNextPending().map(event -> {
            fanout.fanoutPost(event.postId());
            outbox.markProcessed(event.id());
            return true;
        }).orElse(false);
    }
}
