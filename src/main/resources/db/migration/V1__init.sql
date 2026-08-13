CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nickname VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE friendships (
    user_low BIGINT NOT NULL,
    user_high BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_low, user_high),
    CONSTRAINT chk_friend_order CHECK (user_low < user_high),
    CONSTRAINT fk_friend_low FOREIGN KEY (user_low) REFERENCES users(id),
    CONSTRAINT fk_friend_high FOREIGN KEY (user_high) REFERENCES users(id),
    INDEX idx_friend_high_status (user_high, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE blocks (
    blocker_id BIGINT NOT NULL,
    blocked_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT chk_not_block_self CHECK (blocker_id <> blocked_id),
    CONSTRAINT fk_blocker FOREIGN KEY (blocker_id) REFERENCES users(id),
    CONSTRAINT fk_blocked FOREIGN KEY (blocked_id) REFERENCES users(id),
    INDEX idx_blocks_reverse (blocked_id, blocker_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE posts (
    id CHAR(36) NOT NULL,
    author_id BIGINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    visibility VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_post_author FOREIGN KEY (author_id) REFERENCES users(id),
    INDEX idx_posts_author_time (author_id, published_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE post_acl (
    post_id CHAR(36) NOT NULL,
    target_user_id BIGINT NOT NULL,
    rule_type VARCHAR(16) NOT NULL,
    PRIMARY KEY (post_id, target_user_id, rule_type),
    CONSTRAINT fk_acl_post FOREIGN KEY (post_id) REFERENCES posts(id),
    CONSTRAINT fk_acl_target FOREIGN KEY (target_user_id) REFERENCES users(id),
    INDEX idx_acl_target (target_user_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feed_inbox (
    owner_id BIGINT NOT NULL,
    post_id CHAR(36) NOT NULL,
    author_id BIGINT NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (owner_id, post_id),
    CONSTRAINT fk_inbox_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_inbox_post FOREIGN KEY (post_id) REFERENCES posts(id),
    CONSTRAINT fk_inbox_author FOREIGN KEY (author_id) REFERENCES users(id),
    INDEX idx_inbox_owner_cursor (owner_id, published_at DESC, post_id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at TIMESTAMP(6) NULL,
    last_error VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (aggregate_id, event_type),
    INDEX idx_outbox_pending (status, available_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
