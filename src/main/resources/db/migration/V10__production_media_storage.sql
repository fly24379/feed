ALTER TABLE media_attachments
    ADD COLUMN storage_provider VARCHAR(16) NOT NULL DEFAULT 'LOCAL' AFTER storage_key,
    ADD COLUMN object_status VARCHAR(24) NOT NULL DEFAULT 'READY' AFTER storage_provider,
    ADD COLUMN upload_expires_at TIMESTAMP(6) NULL AFTER object_status,
    ADD COLUMN ready_at TIMESTAMP(6) NULL AFTER upload_expires_at,
    ADD COLUMN preview_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' AFTER ready_at,
    ADD COLUMN preview_storage_key VARCHAR(255) NULL AFTER preview_status,
    ADD COLUMN preview_content_type VARCHAR(100) NULL AFTER preview_storage_key,
    ADD COLUMN preview_size_bytes BIGINT NULL AFTER preview_content_type,
    ADD COLUMN preview_attempts INT NOT NULL DEFAULT 0 AFTER preview_size_bytes,
    ADD COLUMN preview_started_at TIMESTAMP(6) NULL AFTER preview_attempts,
    ADD COLUMN preview_processor_id VARCHAR(128) NULL AFTER preview_started_at,
    ADD COLUMN preview_error VARCHAR(1000) NULL AFTER preview_processor_id,
    ADD INDEX idx_media_upload_cleanup (object_status, upload_expires_at, id),
    ADD INDEX idx_media_preview_dispatch (object_status, preview_status, created_at, id),
    ADD INDEX idx_media_preview_timeout (preview_status, preview_started_at, id);

UPDATE media_attachments
   SET ready_at = created_at
 WHERE object_status = 'READY' AND ready_at IS NULL;
