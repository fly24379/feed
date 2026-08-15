ALTER TABLE users
    ADD COLUMN email VARCHAR(254) NULL AFTER password_hash,
    ADD COLUMN phone VARCHAR(16) NULL AFTER email,
    ADD COLUMN email_verified_at TIMESTAMP(6) NULL AFTER phone,
    ADD COLUMN phone_verified_at TIMESTAMP(6) NULL AFTER email_verified_at,
    ADD COLUMN password_changed_at TIMESTAMP(6) NULL AFTER phone_verified_at,
    ADD CONSTRAINT uk_users_email UNIQUE (email),
    ADD CONSTRAINT uk_users_phone UNIQUE (phone);

CREATE TABLE auth_verification_challenges (
    id CHAR(36) NOT NULL,
    user_id BIGINT NULL,
    purpose VARCHAR(24) NOT NULL,
    channel VARCHAR(8) NOT NULL,
    target VARCHAR(254) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    requested_address VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_verification_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_verification_target (purpose, target, created_at),
    INDEX idx_verification_expiry (expires_at, consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
