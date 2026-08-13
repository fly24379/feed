package com.example.feed.service;

import com.example.feed.api.NotFoundException;
import com.example.feed.repository.NotificationRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(notifications);

    @Test
    void markReadIsRestrictedToNotificationOwner() {
        when(notifications.markRead(7, 99)).thenReturn(false);

        assertThatThrownBy(() -> service.markRead(7, 99)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void markAllReadReturnsAffectedCount() {
        when(notifications.markAllRead(7)).thenReturn(4);

        org.assertj.core.api.Assertions.assertThat(service.markAllRead(7)).isEqualTo(4);
        verify(notifications).markAllRead(7);
    }
}
