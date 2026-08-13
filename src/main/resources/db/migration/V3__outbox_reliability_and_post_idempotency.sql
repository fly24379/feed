ALTER TABLE users
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER' AFTER password_hash;

ALTER TABLE posts
    ADD COLUMN idempotency_key CHAR(36) NULL AFTER author_id,
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER idempotency_key;

UPDATE posts
   SET idempotency_key = id,
       request_fingerprint = SHA2(CONCAT('legacy:', id), 256)
 WHERE idempotency_key IS NULL;

ALTER TABLE posts
    MODIFY idempotency_key CHAR(36) NOT NULL,
    MODIFY request_fingerprint CHAR(64) NOT NULL,
    ADD CONSTRAINT uk_posts_author_idempotency UNIQUE (author_id, idempotency_key);

ALTER TABLE outbox_events
    ADD COLUMN processing_started_at TIMESTAMP(6) NULL AFTER available_at,
    ADD COLUMN processor_id VARCHAR(128) NULL AFTER processing_started_at,
    ADD COLUMN replay_count INT NOT NULL DEFAULT 0 AFTER last_error,
    ADD COLUMN replayed_at TIMESTAMP(6) NULL AFTER replay_count,
    DROP INDEX idx_outbox_pending,
    ADD INDEX idx_outbox_dispatch (status, available_at, id),
    ADD INDEX idx_outbox_timeout (status, processing_started_at, id);
