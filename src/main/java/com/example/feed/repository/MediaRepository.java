package com.example.feed.repository;

import com.example.feed.domain.MediaAttachment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MediaRepository {
    private final JdbcClient jdbc;

    public MediaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(StoredMedia media) {
        jdbc.sql("""
                INSERT INTO media_attachments(id, owner_id, media_type, content_type,
                                              original_filename, storage_key, size_bytes)
                VALUES (:id, :ownerId, :mediaType, :contentType, :filename, :storageKey, :sizeBytes)
                """).param("id", media.id()).param("ownerId", media.ownerId())
                .param("mediaType", media.mediaType()).param("contentType", media.contentType())
                .param("filename", media.originalFilename()).param("storageKey", media.storageKey())
                .param("sizeBytes", media.sizeBytes()).update();
    }

    public Optional<StoredMedia> find(String mediaId) {
        return jdbc.sql("""
                SELECT id, owner_id, post_id, media_type, content_type, original_filename,
                       storage_key, size_bytes, created_at
                  FROM media_attachments WHERE id = :id
                """).param("id", mediaId).query(this::mapStored).optional();
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
                """).param("ids", mediaIds).param("ownerId", ownerId).query(Long.class).single();
        if (attachable != mediaIds.size()) {
            throw new IllegalArgumentException("附件不存在、已被使用或不属于当前用户");
        }
        int updated = jdbc.sql("""
                UPDATE media_attachments SET post_id = :postId
                 WHERE id IN (:ids) AND owner_id = :ownerId AND post_id IS NULL
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
                SELECT id, post_id, media_type, content_type, original_filename, size_bytes, created_at
                  FROM media_attachments WHERE post_id IN (:postIds)
                 ORDER BY created_at, id
                """).param("postIds", postIds).query((rs, rowNum) -> Map.entry(rs.getString("post_id"),
                        new MediaAttachment(rs.getString("id"), rs.getString("media_type"),
                                rs.getString("content_type"), rs.getString("original_filename"),
                                rs.getLong("size_bytes"), "/api/media/" + rs.getString("id") + "/content",
                                rs.getTimestamp("created_at").toInstant())))
                .list().forEach(entry -> result.computeIfAbsent(entry.getKey(), ignored -> new java.util.ArrayList<>())
                        .add(entry.getValue()));
        return result;
    }

    public boolean deleteUnattached(String mediaId, long ownerId) {
        return jdbc.sql("DELETE FROM media_attachments WHERE id = :id AND owner_id = :ownerId AND post_id IS NULL")
                .param("id", mediaId).param("ownerId", ownerId).update() == 1;
    }

    private StoredMedia mapStored(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new StoredMedia(rs.getString("id"), rs.getLong("owner_id"), rs.getString("post_id"),
                rs.getString("media_type"), rs.getString("content_type"),
                rs.getString("original_filename"), rs.getString("storage_key"), rs.getLong("size_bytes"),
                rs.getTimestamp("created_at").toInstant());
    }

    public record StoredMedia(String id, long ownerId, String postId, String mediaType,
                              String contentType, String originalFilename, String storageKey,
                              long sizeBytes, java.time.Instant createdAt) {
    }
}
