package com.example.feed.repository;

import com.example.feed.domain.FriendRequest;
import com.example.feed.domain.FriendRequestStatus;
import com.example.feed.domain.UserProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class FriendRequestRepository {
    private final JdbcClient jdbc;

    public FriendRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FriendRequest> find(long requestId) {
        return baseQuery("WHERE r.id = :id").param("id", requestId).query(this::map).optional();
    }

    public Optional<FriendRequest> findPendingBetween(long first, long second) {
        return baseQuery("""
                WHERE r.status = 'PENDING'
                  AND ((r.requester_id = :first AND r.recipient_id = :second)
                       OR (r.requester_id = :second AND r.recipient_id = :first))
                """).param("first", first).param("second", second).query(this::map).optional();
    }

    public FriendRequest createOrReopen(long requesterId, long recipientId) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO friend_requests(requester_id, recipient_id, status)
                VALUES (:requester, :recipient, 'PENDING')
                ON DUPLICATE KEY UPDATE requester_id = VALUES(requester_id),
                                        recipient_id = VALUES(recipient_id), status = 'PENDING',
                                        created_at = CURRENT_TIMESTAMP(6),
                                        updated_at = CURRENT_TIMESTAMP(6), responded_at = NULL,
                                        id = LAST_INSERT_ID(id)
                """).param("requester", requesterId).param("recipient", recipientId).update(keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            id = jdbc.sql("""
                    SELECT id FROM friend_requests
                     WHERE requester_id = :requester AND recipient_id = :recipient
                    """).param("requester", requesterId).param("recipient", recipientId)
                    .query(Long.class).single();
        }
        return find(id.longValue()).orElseThrow();
    }

    public boolean transition(long requestId, long actorId, boolean actorIsRecipient,
                              FriendRequestStatus next) {
        String actorColumn = actorIsRecipient ? "recipient_id" : "requester_id";
        return jdbc.sql("""
                UPDATE friend_requests SET status = :status, responded_at = CURRENT_TIMESTAMP(6)
                 WHERE id = :id AND status = 'PENDING' AND """ + actorColumn + " = :actor")
                .param("status", next.name()).param("id", requestId).param("actor", actorId)
                .update() == 1;
    }

    public void rejectPendingBetween(long first, long second) {
        jdbc.sql("""
                UPDATE friend_requests SET status = 'REJECTED', responded_at = CURRENT_TIMESTAMP(6)
                 WHERE status = 'PENDING'
                   AND ((requester_id = :first AND recipient_id = :second)
                        OR (requester_id = :second AND recipient_id = :first))
                """).param("first", first).param("second", second).update();
    }

    public List<FriendRequest> findForUser(long userId, boolean incoming,
                                           FriendRequestStatus status, long beforeId, int limit) {
        String ownerColumn = incoming ? "r.recipient_id" : "r.requester_id";
        return baseQuery("WHERE " + ownerColumn + " = :userId AND r.status = :status "
                        + "AND r.id < :beforeId ORDER BY r.id DESC LIMIT :limit")
                .param("userId", userId).param("status", status.name())
                .param("beforeId", beforeId).param("limit", limit).query(this::map).list();
    }

    private JdbcClient.StatementSpec baseQuery(String suffix) {
        return jdbc.sql("""
                SELECT r.id, r.status, r.created_at, r.updated_at,
                       requester.id requester_id, requester.username requester_username,
                       requester.nickname requester_nickname, requester.bio requester_bio,
                       requester.avatar_url requester_avatar_url,
                       recipient.id recipient_id, recipient.username recipient_username,
                       recipient.nickname recipient_nickname, recipient.bio recipient_bio,
                       recipient.avatar_url recipient_avatar_url
                  FROM friend_requests r
                  JOIN users requester ON requester.id = r.requester_id
                  JOIN users recipient ON recipient.id = r.recipient_id
                """ + suffix);
    }

    private FriendRequest map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        UserProfile requester = new UserProfile(rs.getLong("requester_id"),
                rs.getString("requester_username"), rs.getString("requester_nickname"),
                rs.getString("requester_bio"), rs.getString("requester_avatar_url"));
        UserProfile recipient = new UserProfile(rs.getLong("recipient_id"),
                rs.getString("recipient_username"), rs.getString("recipient_nickname"),
                rs.getString("recipient_bio"), rs.getString("recipient_avatar_url"));
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new FriendRequest(rs.getLong("id"), requester, recipient,
                FriendRequestStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(), updatedAt.toInstant());
    }
}
