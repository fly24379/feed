CREATE TABLE feed_author_policy (
    author_id BIGINT NOT NULL,
    fanout_mode VARCHAR(16) NOT NULL DEFAULT 'PUSH',
    reason VARCHAR(128) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (author_id),
    CONSTRAINT fk_feed_author_policy_user FOREIGN KEY (author_id) REFERENCES users(id),
    INDEX idx_feed_author_policy_mode (fanout_mode, author_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE posts
    ADD COLUMN delivery_mode VARCHAR(16) NOT NULL DEFAULT 'PUSH' AFTER visibility,
    ADD INDEX idx_posts_pull_timeline
        (author_id, delivery_mode, status, published_at DESC, id DESC);
