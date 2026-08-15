CREATE TABLE auth_sessions (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_used_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    client_address VARCHAR(64) NULL,
    user_agent VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_auth_session_user (user_id, revoked_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE auth_refresh_tokens (
    id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6) NULL,
    revoked_at TIMESTAMP(6) NULL,
    replaced_by_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_auth_refresh_session FOREIGN KEY (session_id) REFERENCES auth_sessions(id) ON DELETE CASCADE,
    INDEX idx_auth_refresh_session (session_id, revoked_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
