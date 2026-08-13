ALTER TABLE users
    ADD COLUMN bio VARCHAR(500) NOT NULL DEFAULT '' AFTER nickname,
    ADD COLUMN avatar_url VARCHAR(500) NULL AFTER bio;

CREATE TABLE friend_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    requester_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    pair_low BIGINT GENERATED ALWAYS AS (LEAST(requester_id, recipient_id)) STORED,
    pair_high BIGINT GENERATED ALWAYS AS (GREATEST(requester_id, recipient_id)) STORED,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    responded_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_friend_request_users CHECK (requester_id <> recipient_id),
    CONSTRAINT uk_friend_request_pair UNIQUE (pair_low, pair_high),
    CONSTRAINT fk_friend_request_requester FOREIGN KEY (requester_id) REFERENCES users(id),
    CONSTRAINT fk_friend_request_recipient FOREIGN KEY (recipient_id) REFERENCES users(id),
    INDEX idx_friend_request_inbox (recipient_id, status, id DESC),
    INDEX idx_friend_request_outbox (requester_id, status, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    actor_id BIGINT NULL,
    entity_type VARCHAR(32) NULL,
    entity_id VARCHAR(64) NULL,
    message VARCHAR(500) NOT NULL,
    read_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_notification_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    INDEX idx_notification_page (user_id, id DESC),
    INDEX idx_notification_unread (user_id, read_at, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE post_likes (
    post_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (post_id, user_id),
    CONSTRAINT fk_like_post FOREIGN KEY (post_id) REFERENCES posts(id),
    CONSTRAINT fk_like_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_like_user_time (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE post_comments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id CHAR(36) NOT NULL,
    author_id BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_comment_post FOREIGN KEY (post_id) REFERENCES posts(id),
    CONSTRAINT fk_comment_author FOREIGN KEY (author_id) REFERENCES users(id),
    INDEX idx_comment_post_page (post_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE media_attachments (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    post_id CHAR(36) NULL,
    media_type VARCHAR(16) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_media_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_media_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_media_post FOREIGN KEY (post_id) REFERENCES posts(id),
    INDEX idx_media_post (post_id, id),
    INDEX idx_media_unattached (owner_id, post_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
