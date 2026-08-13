package com.example.feed.service;

import com.example.feed.api.ConflictException;
import com.example.feed.api.NotFoundException;
import com.example.feed.repository.OutboxRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxAdminServiceTest {
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final OutboxAdminService service = new OutboxAdminService(outbox);

    @Test
    void replaysFailedEvent() {
        when(outbox.replayFailed(10)).thenReturn(true);
        service.replay(10);
        verify(outbox).replayFailed(10);
    }

    @Test
    void rejectsReplayOfActiveEvent() {
        when(outbox.findStatus(10)).thenReturn(Optional.of("PROCESSING"));
        assertThatThrownBy(() -> service.replay(10))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PROCESSING");
    }

    @Test
    void reportsMissingEvent() {
        when(outbox.findStatus(10)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.replay(10)).isInstanceOf(NotFoundException.class);
    }
}
