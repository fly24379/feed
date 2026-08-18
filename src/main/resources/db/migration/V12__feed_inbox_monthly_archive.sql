CREATE TABLE feed_inbox_archive (
    archive_month DATE NOT NULL,
    owner_id BIGINT NOT NULL,
    post_id CHAR(36) NOT NULL,
    author_id BIGINT NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    archived_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (owner_id, post_id),
    INDEX idx_inbox_archive_month (archive_month, author_id, published_at, post_id),
    INDEX idx_inbox_archive_owner_cursor (owner_id, published_at DESC, post_id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE feed_inbox
    ADD INDEX idx_inbox_archive_cursor (published_at, post_id, owner_id);
