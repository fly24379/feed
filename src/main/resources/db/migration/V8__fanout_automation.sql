ALTER TABLE feed_author_policy
    ADD COLUMN policy_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL' AFTER fanout_mode,
    ADD COLUMN evaluated_friend_count BIGINT NULL AFTER reason,
    ADD COLUMN evaluated_at TIMESTAMP(6) NULL AFTER evaluated_friend_count,
    ADD INDEX idx_feed_author_policy_source (policy_source, fanout_mode, author_id);
