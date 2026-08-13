package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.domain.UserProfile;
import com.example.feed.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {
    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserProfile get(long userId) {
        return users.requireProfile(userId);
    }

    @Transactional
    public UserProfile update(long userId, String nickname, String bio, String avatarUrl) {
        UserProfile current = users.requireProfile(userId);
        String nextNickname = nickname == null ? current.nickname() : nickname.strip();
        if (nextNickname.isBlank()) {
            throw new BadRequestException("昵称不能为空");
        }
        String nextBio = bio == null ? current.bio() : bio.strip();
        String nextAvatar = avatarUrl == null ? current.avatarUrl() : avatarUrl.strip();
        if (nextAvatar != null && nextAvatar.isBlank()) {
            nextAvatar = null;
        }
        users.updateProfile(userId, nextNickname, nextBio, nextAvatar);
        return users.requireProfile(userId);
    }

    @Transactional(readOnly = true)
    public UserPage search(String query, Long afterId, Integer requestedSize) {
        String keyword = query == null ? "" : query.strip();
        if (keyword.isBlank()) {
            throw new BadRequestException("搜索关键词不能为空");
        }
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 100));
        List<UserProfile> loaded = users.search(keyword, afterId == null ? 0 : Math.max(0, afterId), size + 1);
        boolean hasMore = loaded.size() > size;
        List<UserProfile> items = hasMore ? List.copyOf(loaded.subList(0, size)) : List.copyOf(loaded);
        Long nextAfterId = hasMore ? items.getLast().id() : null;
        return new UserPage(items, nextAfterId, hasMore);
    }

    public record UserPage(List<UserProfile> items, Long nextAfterId, boolean hasMore) {
    }
}
