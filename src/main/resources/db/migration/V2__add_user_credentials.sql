ALTER TABLE users
    ADD COLUMN username VARCHAR(32) NULL AFTER id,
    ADD COLUMN password_hash VARCHAR(100) NULL AFTER nickname;

-- Existing MVP users had no credentials. Keep their data but make login impossible.
UPDATE users
   SET username = CONCAT('legacy_', id),
       password_hash = 'ACCOUNT_DISABLED'
 WHERE username IS NULL;

ALTER TABLE users
    MODIFY username VARCHAR(32) NOT NULL,
    MODIFY password_hash VARCHAR(100) NOT NULL,
    ADD CONSTRAINT uk_users_username UNIQUE (username);
