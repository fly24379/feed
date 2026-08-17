CREATE TABLE follows (
    follower_id BIGINT NOT NULL,
    followee_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT chk_follow_users CHECK (follower_id <> followee_id),
    CONSTRAINT fk_follow_follower FOREIGN KEY (follower_id) REFERENCES users(id),
    CONSTRAINT fk_follow_followee FOREIGN KEY (followee_id) REFERENCES users(id),
    INDEX idx_follows_followee_page (followee_id, follower_id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve the existing social graph: an active friendship becomes two follows.
INSERT IGNORE INTO follows(follower_id, followee_id, created_at)
SELECT user_low, user_high, created_at FROM friendships WHERE status = 'ACTIVE';

INSERT IGNORE INTO follows(follower_id, followee_id, created_at)
SELECT user_high, user_low, created_at FROM friendships WHERE status = 'ACTIVE';
