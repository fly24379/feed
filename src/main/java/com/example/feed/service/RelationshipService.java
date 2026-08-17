package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.api.ConflictException;
import com.example.feed.api.ForbiddenException;
import com.example.feed.api.NotFoundException;
import com.example.feed.domain.FriendRequest;
import com.example.feed.domain.FriendRequestStatus;
import com.example.feed.domain.UserProfile;
import com.example.feed.repository.FriendRequestRepository;
import com.example.feed.repository.NotificationRepository;
import com.example.feed.repository.RelationshipRepository;
import com.example.feed.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RelationshipService {
    private final UserRepository users;
    private final RelationshipRepository relationships;
    private final FriendRequestRepository requests;
    private final NotificationRepository notifications;

    @Value("${feed.follow.backfill-limit:200}")
    private int followBackfillLimit = 200;

    public RelationshipService(UserRepository users, RelationshipRepository relationships,
                               FriendRequestRepository requests, NotificationRepository notifications) {
        this.users = users;
        this.relationships = relationships;
        this.requests = requests;
        this.notifications = notifications;
    }

    @Transactional
    public FriendRequest sendRequest(long userId, long recipientId) {
        requireDistinct(userId, recipientId);
        users.requireExists(userId);
        users.requireExists(recipientId);
        if (relationships.isBlockedEitherDirection(userId, recipientId)) {
            throw new ForbiddenException("存在拉黑关系，无法发送好友申请");
        }
        if (relationships.isActiveFriend(userId, recipientId)) {
            throw new ConflictException("你们已经是好友");
        }
        requests.findPendingBetween(userId, recipientId).ifPresent(existing -> {
            if (existing.requester().id() == userId) {
                throw new ConflictException("好友申请已发送");
            }
            throw new ConflictException("对方已向你发送好友申请，请直接处理该申请");
        });
        FriendRequest request = requests.createOrReopen(userId, recipientId);
        notifications.add(recipientId, "FRIEND_REQUEST", userId, "FRIEND_REQUEST",
                Long.toString(request.id()), request.requester().nickname() + " 请求添加你为好友");
        return request;
    }

    @Transactional
    public FriendRequest accept(long userId, long requestId) {
        FriendRequest request = requireRequest(requestId);
        requireRecipient(userId, request);
        if (relationships.isBlockedEitherDirection(request.requester().id(), request.recipient().id())) {
            throw new ForbiddenException("存在拉黑关系，无法接受好友申请");
        }
        if (!requests.transition(requestId, userId, true, FriendRequestStatus.ACCEPTED)) {
            throw new ConflictException("好友申请已处理");
        }
        relationships.addFriend(request.requester().id(), request.recipient().id());
        relationships.backfillRecentPushPosts(request.requester().id(), request.recipient().id(),
                followBackfillLimit);
        relationships.backfillRecentPushPosts(request.recipient().id(), request.requester().id(),
                followBackfillLimit);
        notifications.add(request.requester().id(), "FRIEND_REQUEST_ACCEPTED", userId,
                "FRIEND_REQUEST", Long.toString(request.id()),
                request.recipient().nickname() + " 已接受你的好友申请");
        return requests.find(requestId).orElseThrow();
    }

    @Transactional
    public FriendRequest reject(long userId, long requestId) {
        FriendRequest request = requireRequest(requestId);
        requireRecipient(userId, request);
        if (!requests.transition(requestId, userId, true, FriendRequestStatus.REJECTED)) {
            throw new ConflictException("好友申请已处理");
        }
        return requests.find(requestId).orElseThrow();
    }

    @Transactional
    public FriendRequest withdraw(long userId, long requestId) {
        FriendRequest request = requireRequest(requestId);
        if (request.requester().id() != userId) {
            throw new ForbiddenException("只能撤回自己发送的好友申请");
        }
        if (!requests.transition(requestId, userId, false, FriendRequestStatus.WITHDRAWN)) {
            throw new ConflictException("好友申请已处理");
        }
        return requests.find(requestId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public RequestPage listRequests(long userId, boolean incoming, FriendRequestStatus status,
                                    Long beforeId, Integer requestedSize) {
        users.requireExists(userId);
        int size = requestedSize == null ? 20 : Math.max(1, Math.min(requestedSize, 100));
        var loaded = requests.findForUser(userId, incoming, status,
                beforeId == null ? Long.MAX_VALUE : beforeId, size + 1);
        boolean hasMore = loaded.size() > size;
        var items = hasMore ? java.util.List.copyOf(loaded.subList(0, size)) : java.util.List.copyOf(loaded);
        Long nextBeforeId = hasMore ? items.getLast().id() : null;
        return new RequestPage(items, nextBeforeId, hasMore);
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
        relationships.removeFriend(blockerId, blockedId);
        requests.rejectPendingBetween(blockerId, blockedId);
    }

    @Transactional
    public void unblock(long blockerId, long blockedId) {
        relationships.unblock(blockerId, blockedId);
    }

    @Transactional
    public FollowState follow(long followerId, long followeeId) {
        requireDistinct(followerId, followeeId);
        users.requireExists(followerId);
        UserProfile followee = users.requireProfile(followeeId);
        if (relationships.isBlockedEitherDirection(followerId, followeeId)) {
            throw new ForbiddenException("存在拉黑关系，无法关注该用户");
        }
        boolean created = relationships.follow(followerId, followeeId);
        int backfilledPosts = created
                ? relationships.backfillRecentPushPosts(followerId, followeeId, followBackfillLimit) : 0;
        if (created) {
            UserProfile follower = users.requireProfile(followerId);
            notifications.add(followeeId, "NEW_FOLLOWER", followerId, "USER",
                    Long.toString(followerId), follower.nickname() + " 关注了你");
        }
        return followState(followerId, followee, backfilledPosts);
    }

    @Transactional
    public FollowState unfollow(long followerId, long followeeId) {
        requireDistinct(followerId, followeeId);
        users.requireExists(followerId);
        UserProfile followee = users.requireProfile(followeeId);
        relationships.unfollow(followerId, followeeId);
        return followState(followerId, followee, 0);
    }

    @Transactional(readOnly = true)
    public FollowPage listFollowing(long userId, Long beforeUserId, Integer requestedSize) {
        users.requireExists(userId);
        return followPage(relationships.findFollowing(userId,
                beforeUserId == null ? Long.MAX_VALUE : beforeUserId,
                pageSize(requestedSize) + 1), pageSize(requestedSize));
    }

    @Transactional(readOnly = true)
    public FollowPage listFollowers(long userId, Long beforeUserId, Integer requestedSize) {
        users.requireExists(userId);
        return followPage(relationships.findFollowers(userId,
                beforeUserId == null ? Long.MAX_VALUE : beforeUserId,
                pageSize(requestedSize) + 1), pageSize(requestedSize));
    }

    private int pageSize(Integer requestedSize) {
        return requestedSize == null ? 50 : Math.max(1, Math.min(requestedSize, 100));
    }

    private FollowPage followPage(java.util.List<UserProfile> loaded, int size) {
        boolean hasMore = loaded.size() > size;
        var items = hasMore ? java.util.List.copyOf(loaded.subList(0, size))
                : java.util.List.copyOf(loaded);
        Long nextBeforeUserId = hasMore ? items.getLast().id() : null;
        return new FollowPage(items, nextBeforeUserId, hasMore);
    }

    @Transactional(readOnly = true)
    public FollowState getFollowState(long viewerId, long userId) {
        users.requireExists(viewerId);
        return followState(viewerId, users.requireProfile(userId), 0);
    }

    private FollowState followState(long viewerId, UserProfile profile, int backfilledPosts) {
        var stats = relationships.findFollowStats(viewerId, profile.id());
        return new FollowState(profile, stats.followingCount(), stats.followerCount(),
                stats.followedByViewer(), stats.followsViewer(), backfilledPosts);
    }

    private void requireDistinct(long first, long second) {
        if (first == second) {
            throw new BadRequestException("不能对自己执行该关系操作");
        }
    }

    @Transactional(readOnly = true)
    public java.util.List<UserProfile> listFriends(long userId) {
        users.requireExists(userId);
        return relationships.findFriends(userId);
    }

    @Transactional(readOnly = true)
    public java.util.List<UserProfile> listBlocked(long userId) {
        users.requireExists(userId);
        return relationships.findBlockedUsers(userId);
    }

    private FriendRequest requireRequest(long requestId) {
        return requests.find(requestId).orElseThrow(() -> new NotFoundException("好友申请不存在: " + requestId));
    }

    private void requireRecipient(long userId, FriendRequest request) {
        if (request.recipient().id() != userId) {
            throw new ForbiddenException("只能处理发给自己的好友申请");
        }
    }

    public record RequestPage(java.util.List<FriendRequest> items, Long nextBeforeId, boolean hasMore) {
    }

    public record FollowState(UserProfile user, long followingCount, long followerCount,
                              boolean followedByMe, boolean followsMe, int backfilledPosts) {
    }

    public record FollowPage(java.util.List<UserProfile> items, Long nextBeforeUserId,
                             boolean hasMore) {
    }
}
