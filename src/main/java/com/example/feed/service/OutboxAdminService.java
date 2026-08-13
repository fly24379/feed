package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.api.NotFoundException;
import com.example.feed.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxAdminService {
    private final OutboxRepository outbox;

    public OutboxAdminService(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional
    public void replay(long eventId) {
        if (outbox.replayFailed(eventId)) {
            return;
        }
        String status = outbox.findStatus(eventId)
                .orElseThrow(() -> new NotFoundException("Outbox 事件不存在: " + eventId));
        throw new ConflictException("只有 FAILED 事件可以重放，当前状态: " + status);
    }
}
