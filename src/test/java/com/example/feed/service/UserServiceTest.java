package com.example.feed.service;

import com.example.feed.domain.UserProfile;
import com.example.feed.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final UserService service = new UserService(users);

    @Test
    void partialProfileUpdateKeepsUnspecifiedFields() {
        UserProfile before = new UserProfile(1, "alice", "Alice", "old bio", "https://old/avatar");
        UserProfile after = new UserProfile(1, "alice", "New Alice", "old bio", "https://old/avatar");
        when(users.requireProfile(1)).thenReturn(before, after);

        assertThat(service.update(1, " New Alice ", null, null)).isEqualTo(after);
        verify(users).updateProfile(1, "New Alice", "old bio", "https://old/avatar");
    }

    @Test
    void searchUsesBoundedKeysetPage() {
        UserProfile first = new UserProfile(2, "alice", "Alice", "", null);
        UserProfile second = new UserProfile(3, "alice2", "Alice 2", "", null);
        when(users.search("alice", 0, 2)).thenReturn(List.of(first, second));

        UserService.UserPage page = service.search(" alice ", null, 1);

        assertThat(page.items()).containsExactly(first);
        assertThat(page.nextAfterId()).isEqualTo(2);
        assertThat(page.hasMore()).isTrue();
    }
}
