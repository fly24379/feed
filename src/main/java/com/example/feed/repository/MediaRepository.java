package com.example.feed.repository;

import com.example.feed.domain.MediaAttachment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MediaRepository {
    private static final String STORED_COLUMNS = """
            id, owner_id, post_id, media_type, content_type, original_filename,
            storage_key, storage_provider, object_status, upload_expires_at, ready_at,
            size_bytes, preview_status, preview_storage_key, preview_content_type,
            preview_size_bytes, preview_attempts, preview_started_at, preview_processor_id,
            preview_error, created_at
            """;

    private final JdbcClient jdbc;

    public MediaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(StoredMedia value) {
        jdbc.sql("""
                INSERT INTO media_attachments(
                    id, owner_id, media_type, content_type, original_filename, storage_key,
                    storage_provider, object_status, upload_expires_at, ready_at, size_bytes,
                    preview_status, created_at)
                VALUES (:id, :ownerId, :mediaType, :contentType, :filename, :storageKey,
                        :provider, :objectStatus, :uploadExpiresAt, :readyAt, :sizeBytes,
                        :previewStatus, :createdAt)
                """).param("id", value.id()).param("ownerId", value.ownerId())
                .param("mediaType", value.mediaType()).param("contentType", value.contentType())
                .param("filename", value.originalFilename()).param("storageKey", value.storageKey())
                .param("provider", value.storageProvider()).param("objectStatus", value.objectStatus())
                .param("uploadExpiresAt", value.uploadExpiresAt()).param("readyAt", value.readyAt())
                .param("sizeBytes", value.sizeBytes()).param("previewStatus", value.previewStatus())
                .param("createdAt", value.createdAt()).update();
    }

    public Optional<StoredMedia> find(String mediaId) {
        return jdbc.sql("SELECT " + STORED_COLUMNS + " FROM media_attachments WHERE id = :id")
                .param("id", mediaId).query(this::mapStored).optional();
    }

    public boolean markReady(String mediaId, long ownerId, Instant readyAt) {
        return jdbc.sql("""
                UPDATE media_attachments
                   SET object_status = 'READY', ready_at = :readyAt, upload_expires_at = NULL
                 WHERE id = :id AND owner_id = :ownerId AND post_id IS NULL
                   AND object_status = 'PENDING_UPLOAD' AND upload_expires_at >= :readyAt
                """).param("id", mediaId).param("ownerId", ownerId)
                .param("readyAt", readyAt).update() == 1;
    }

    public void attachToPost(long ownerId, String postId, Collection<String> mediaIds, int maxPerPost) {
        if (mediaIds.isEmpty()) {
            return;
        }
        if (mediaIds.size() > maxPerPost) {
            throw new IllegalArgumentException("每条动态最多上传 " + maxPerPost + " 个附件");
        }
        long attachable = jdbc.sql("""
                SELECT COUNT(*) FROM media_attachments
                 WHERE id IN (:ids) AND owner_id = :ownerId AND post_id IS NULL
                   AND object_status = 'READY'
                """).param("ids", mediaIds).param("ownerId", ownerId).query(Long.class).single();
        if (attachable != mediaIds.size()) {
            throw new IllegalArgumentException("附件不存在、尚未上传完成、已被使用或不属于当前用户");
        }
        int updated = jdbc.sql("""
                UPDATE media_attachments SET post_id = :postId
                 WHERE id IN (:ids) AND owner_id = :ownerId AND post_id IS NULL
                   AND object_status = 'READY'
                """).param("postId", postId).param("ids", mediaIds).param("ownerId", ownerId).update();
        if (updated != mediaIds.size()) {
            throw new IllegalStateException("附件绑定发生并发冲突");
        }
    }

    public Map<String, List<MediaAttachment>> findByPosts(Collection<String> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<MediaAttachment>> result = new HashMap<>();
        jdbc.sql("""
                SELECT id, post_id, media_type, content_type, original_filename, size_bytes,
                       preview_status, created_at
                  FROM media_attachments
                 WHERE post_id IN (:postIds) AND object_status = 'READY'
                 ORDER BY created_at, id
                """).param("postIds", postIds).query((rs, rowNum) -> Map.entry(rs.getString("post_id"),
                        new MediaAttachment(rs.getString("id"), rs.getString("media_type"),
                                rs.getString("content_type"), rs.getString("original_filename"),
                                rs.getLong("size_bytes"), "/api/media/" + rs.getString("id") + "/content",
                                "READY".equals(rs.getString("preview_status"))
                                        ? "/api/media/" + rs.getString("id") + "/preview" : null,
                                rs.getString("preview_status"), rs.getTimestamp("created_at").toInstant())))
                .list().forEach(entry -> result.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(entry.getValue()));
        return result;
    }

    public boolean markDeletingByOwner(String mediaId, long ownerId) {
        return jdbc.sql("""
                UPDATE media_attachments SET object_status = 'DELETING'
                 WHERE id = :id AND owner_id = :ownerId AND post_id IS NULL
                   AND object_status IN ('PENDING_UPLOAD', 'READY')
                """).param("id", mediaId).param("ownerId", ownerId).update() == 1;
    }

    public Optional<StoredMedia> claimNextPreview(String processorId) {
        int claimed = jdbc.sql("""
                UPDATE media_attachments
                   SET preview_status = 'PROCESSING', preview_started_at = CURRENT_TIMESTAMP(6),
                       preview_processor_id = :processorId, preview_error = NULL
                 WHERE object_status = 'READY' AND preview_status = 'PENDING'
                 ORDER BY created_at, id LIMIT 1
                """).param("processorId", processorId).update();
        if (claimed == 0) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + STORED_COLUMNS + " FROM media_attachments "
                        + "WHERE preview_status = 'PROCESSING' AND preview_processor_id = :processorId "
                        + "ORDER BY preview_started_at DESC LIMIT 1")
                .param("processorId", processorId).query(this::mapStored).optional();
    }

    public boolean markPreviewReady(String mediaId, String processorId, String storageKey,
                                    String contentType, long sizeBytes) {
        return jdbc.sql("""
                UPDATE media_attachments
                   SET preview_status = 'READY', preview_storage_key = :storageKey,
                       preview_content_type = :contentType, preview_size_bytes = :sizeBytes,
                       preview_started_at = NULL, preview_processor_id = NULL, preview_error = NULL
                 WHERE id = :id AND preview_status = 'PROCESSING'
                   AND preview_processor_id = :processorId
                """).param("id", mediaId).param("processorId", processorId)
                .param("storageKey", storageKey).param("contentType", contentType)
                .param("sizeBytes", sizeBytes).update() == 1;
    }

    public boolean markPreviewFailure(String mediaId, String processorId, String error, int maxAttempts) {
        return jdbc.sql("""
                UPDATE media_attachments
                   SET preview_attempts = preview_attempts + 1,
                       preview_status = CASE WHEN preview_attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                       preview_started_at = NULL, preview_processor_id = NULL, preview_error = :error
                 WHERE id = :id AND preview_status = 'PROCESSING'
                   AND preview_processor_id = :processorId
                """).param("id", mediaId).param("processorId", processorId)
                .param("error", truncate(error, 1000)).param("maxAttempts", maxAttempts).update() == 1;
    }

    public int recoverTimedOutPreviews(Instant deadline, int maxAttempts) {
        return jdbc.sql("""
                UPDATE media_attachments
                   SET preview_attempts = preview_attempts + 1,
                       preview_status = CASE WHEN preview_attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                       preview_started_at = NULL, preview_processor_id = NULL,
                       preview_error = '预览处理超时，已自动回收'
                 WHERE preview_status = 'PROCESSING' AND preview_started_at < :deadline
                """).param("deadline", deadline).param("maxAttempts", maxAttempts).update();
    }

    public List<StoredMedia> findCleanupCandidates(Instant unattachedBefore, int limit) {
        return jdbc.sql("SELECT " + STORED_COLUMNS + " FROM media_attachments " + """
                 WHERE post_id IS NULL
                   AND (object_status = 'DELETING'
                     OR (object_status = 'PENDING_UPLOAD' AND upload_expires_at < CURRENT_TIMESTAMP(6))
                     OR (object_status = 'READY' AND created_at < :unattachedBefore))
                 ORDER BY created_at, id LIMIT :limit
                """).param("unattachedBefore", unattachedBefore).param("limit", limit)
                .query(this::mapStored).list();
    }

    public boolean markCleanupDeleting(String mediaId) {
        return jdbc.sql("""
                UPDATE media_attachments SET object_status = 'DELETING'
                 WHERE id = :id AND post_id IS NULL
                   AND object_status IN ('PENDING_UPLOAD', 'READY')
                """).param("id", mediaId).update() == 1;
    }

    public boolean deleteMarked(String mediaId) {
        return jdbc.sql("""
                DELETE FROM media_attachments
                 WHERE id = :id AND post_id IS NULL AND object_status = 'DELETING'
                """).param("id", mediaId).update() == 1;
    }

    private StoredMedia mapStored(ResultSet rs, int rowNum) throws SQLException {
        return new StoredMedia(rs.getString("id"), rs.getLong("owner_id"), rs.getString("post_id"),
                rs.getString("media_type"), rs.getString("content_type"),
                rs.getString("original_filename"), rs.getString("storage_key"),
                rs.getString("storage_provider"), rs.getString("object_status"),
                instant(rs, "upload_expires_at"), instant(rs, "ready_at"), rs.getLong("size_bytes"),
                rs.getString("preview_status"), rs.getString("preview_storage_key"),
                rs.getString("preview_content_type"), nullableLong(rs, "preview_size_bytes"),
                rs.getInt("preview_attempts"), instant(rs, "preview_started_at"),
                rs.getString("preview_processor_id"), rs.getString("preview_error"),
                rs.getTimestamp("created_at").toInstant());
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String truncate(String value, int length) {
        if (value == null || value.isBlank()) {
            return "预览处理失败";
        }
        return value.length() <= length ? value : value.substring(0, length);
    }

    public record StoredMedia(String id, long ownerId, String postId, String mediaType,
                              String contentType, String originalFilename, String storageKey,
                              String storageProvider, String objectStatus, Instant uploadExpiresAt,
                              Instant readyAt, long sizeBytes, String previewStatus,
                              String previewStorageKey, String previewContentType,
                              Long previewSizeBytes, int previewAttempts, Instant previewStartedAt,
                              String previewProcessorId, String previewError, Instant createdAt) {
        public static StoredMedia pending(String id, long ownerId, String mediaType, String contentType,
                                          String filename, String storageKey, String provider,
                                          long sizeBytes, Instant uploadExpiresAt, Instant createdAt) {
            return new StoredMedia(id, ownerId, null, mediaType, contentType, filename, storageKey,
                    provider, "PENDING_UPLOAD", uploadExpiresAt, null, sizeBytes, "PENDING",
                    null, null, null, 0, null, null, null, createdAt);
        }

        public static StoredMedia ready(String id, long ownerId, String mediaType, String contentType,
                                        String filename, String storageKey, String provider,
                                        long sizeBytes, Instant createdAt) {
            return new StoredMedia(id, ownerId, null, mediaType, contentType, filename, storageKey,
                    provider, "READY", null, createdAt, sizeBytes, "PENDING",
                    null, null, null, 0, null, null, null, createdAt);
        }
    }
}
