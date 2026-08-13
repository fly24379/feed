package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.repository.RelationshipRepository;
import com.example.feed.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RelationshipService {
    private final UserRepository users;
    private final RelationshipRepository relationships;

    public RelationshipService(UserRepository users, RelationshipRepository relationships) {
        this.users = users;
        this.relationships = relationships;
    }

    @Transactional
    public void addFriend(long userId, long friendId) {
        requireDistinct(userId, friendId);
        users.requireExists(userId);
        users.requireExists(friendId);
        relationships.addFriend(userId, friendId);
    }

    @Transactional
    public void removeFriend(long userId, long friendId) {
        requireDistinct(userId, friendId);
        relationships.removeFriend(userId, friendId);
    }

    @Transactional
    public void block(long blockerId, long blockedId) {
        requireDistinct(blockerId, blockedId);
        users.requireExists(blockerId);
        users.requireExists(blockedId);
        relationships.block(blockerId, blockedId);
    }

    @Transactional
    public void unblock(long blockerId, long blockedId) {
        relationships.unblock(blockerId, blockedId);
    }

    private void requireDistinct(long first, long second) {
        if (first == second) {
            throw new BadRequestException("不能对自己执行该关系操作");
        }
    }
}
