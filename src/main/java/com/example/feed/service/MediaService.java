package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.api.ForbiddenException;
import com.example.feed.api.NotFoundException;
import com.example.feed.domain.MediaAttachment;
import com.example.feed.repository.MediaRepository;
import com.example.feed.repository.MediaRepository.StoredMedia;
import com.example.feed.service.storage.MediaStorage;
import com.example.feed.service.storage.MediaStorage.PresignedRequest;
import com.example.feed.service.storage.MediaStorageRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", "IMAGE", "image/png", "IMAGE", "image/gif", "IMAGE",
            "image/webp", "IMAGE", "video/mp4", "VIDEO", "video/webm", "VIDEO",
            "video/quicktime", "VIDEO");

    private final MediaRepository media;
    private final PermissionService permissions;
    private final MediaStorageRegistry storages;
    private final long maxBytes;
    private final Duration uploadUrlTtl;
    private final Duration downloadUrlTtl;

    public MediaService(MediaRepository media, PermissionService permissions, MediaStorageRegistry storages,
                        @Value("${feed.media.max-file-size:20MB}") DataSize maxFileSize,
                        @Value("${feed.media.upload-url-ttl:10m}") Duration uploadUrlTtl,
                        @Value("${feed.media.download-url-ttl:5m}") Duration downloadUrlTtl) {
        this.media = media;
        this.permissions = permissions;
        this.storages = storages;
        this.maxBytes = maxFileSize.toBytes();
        this.uploadUrlTtl = uploadUrlTtl;
        this.downloadUrlTtl = downloadUrlTtl;
    }

    @Transactional
    public MediaAttachment upload(long ownerId, MultipartFile file) {
        validate(file.getOriginalFilename(), file.getContentType(), file.getSize(), file.isEmpty());
        String contentType = normalizeType(file.getContentType());
        String mediaType = ALLOWED_TYPES.get(contentType);
        String id = UUID.randomUUID().toString();
        String storageKey = originalKey(ownerId, id);
        String filename = safeFilename(file.getOriginalFilename());
        Instant createdAt = Instant.now();
        MediaStorage storage = storages.defaultStorage();
        try {
            storage.put(storageKey, file.getInputStream(), file.getSize(), contentType);
            deleteOnRollback(storage, storageKey);
            StoredMedia stored = StoredMedia.ready(id, ownerId, mediaType, contentType, filename,
                    storageKey, storage.provider(), file.getSize(), createdAt);
            media.insert(stored);
            return attachment(stored);
        } catch (Exception exception) {
            tryDelete(storage, storageKey);
            if (exception instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("保存媒体文件失败", exception);
        }
    }

    @Transactional
    public UploadTicket initiateUpload(long ownerId, InitiateUploadRequest request) {
        validate(request.filename(), request.contentType(), request.sizeBytes(), request.sizeBytes() <= 0);
        String contentType = normalizeType(request.contentType());
        MediaStorage storage = storages.defaultStorage();
        String id = UUID.randomUUID().toString();
        String storageKey = originalKey(ownerId, id);
        var presigned = storage.presignPut(storageKey, contentType, uploadUrlTtl);
        if (presigned.isEmpty()) {
            return new UploadTicket(null, "PROXY", null, null, Map.of(), null);
        }
        Instant createdAt = Instant.now();
        PresignedRequest upload = presigned.get();
        StoredMedia stored = StoredMedia.pending(id, ownerId, ALLOWED_TYPES.get(contentType), contentType,
                safeFilename(request.filename()), storageKey, storage.provider(), request.sizeBytes(),
                upload.expiresAt(), createdAt);
        media.insert(stored);
        return new UploadTicket(id, "DIRECT", upload.url(), upload.method(), upload.headers(), upload.expiresAt());
    }

    @Transactional
    public MediaAttachment confirmUpload(long ownerId, String mediaId) {
        StoredMedia stored = media.find(mediaId)
                .orElseThrow(() -> new NotFoundException("媒体不存在: " + mediaId));
        requireOwnerAndUnattached(ownerId, stored);
        if ("READY".equals(stored.objectStatus())) {
            return attachment(stored);
        }
        if (!"PENDING_UPLOAD".equals(stored.objectStatus())
                || stored.uploadExpiresAt() == null || stored.uploadExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("上传凭证已过期，请重新上传");
        }
        MediaStorage.ObjectMetadata metadata = storages.require(stored.storageProvider()).head(stored.storageKey())
                .orElseThrow(() -> new BadRequestException("对象尚未上传完成"));
        if (metadata.sizeBytes() != stored.sizeBytes()) {
            throw new BadRequestException("上传对象大小与申请不一致");
        }
        if (metadata.contentType() != null
                && !normalizeType(metadata.contentType()).equals(stored.contentType())) {
            throw new BadRequestException("上传对象类型与申请不一致");
        }
        Instant readyAt = Instant.now();
        if (!media.markReady(mediaId, ownerId, readyAt)) {
            throw new BadRequestException("上传确认失败，凭证可能已过期");
        }
        return attachment(new StoredMedia(stored.id(), stored.ownerId(), stored.postId(), stored.mediaType(),
                stored.contentType(), stored.originalFilename(), stored.storageKey(), stored.storageProvider(),
                "READY", null, readyAt, stored.sizeBytes(), stored.previewStatus(), stored.previewStorageKey(),
                stored.previewContentType(), stored.previewSizeBytes(), stored.previewAttempts(),
                stored.previewStartedAt(), stored.previewProcessorId(), stored.previewError(), stored.createdAt()));
    }

    @Transactional(readOnly = true)
    public MediaAccess access(long viewerId, String mediaId, String requestedVariant) {
        StoredMedia stored = requireVisible(viewerId, mediaId);
        Variant variant = Variant.parse(requestedVariant);
        ObjectView object = object(stored, variant);
        MediaStorage storage = storages.require(stored.storageProvider());
        String disposition = "inline; filename*=UTF-8''"
                + URLEncoder.encode(object.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return storage.presignGet(object.key(), object.contentType(), disposition, downloadUrlTtl)
                .map(value -> new MediaAccess(value.url(), value.expiresAt(), variant.name()))
                .orElseGet(() -> new MediaAccess("/api/media/" + mediaId + "/"
                        + (variant == Variant.PREVIEW ? "preview" : "content"), null, variant.name()));
    }

    @Transactional(readOnly = true)
    public MediaContent content(long viewerId, String mediaId, String requestedVariant) {
        StoredMedia stored = requireVisible(viewerId, mediaId);
        ObjectView object = object(stored, Variant.parse(requestedVariant));
        try {
            return new MediaContent(new InputStreamResource(
                    storages.require(stored.storageProvider()).read(object.key())), object.contentType(),
                    object.filename(), object.sizeBytes());
        } catch (IOException exception) {
            throw new NotFoundException("媒体文件不存在: " + mediaId);
        }
    }

    public MediaContent content(long viewerId, String mediaId) {
        return content(viewerId, mediaId, "ORIGINAL");
    }

    @Transactional
    public void deleteUnattached(long ownerId, String mediaId) {
        StoredMedia stored = media.find(mediaId)
                .orElseThrow(() -> new NotFoundException("媒体不存在: " + mediaId));
        requireOwnerAndUnattached(ownerId, stored);
        if (!media.markDeletingByOwner(mediaId, ownerId)) {
            throw new NotFoundException("媒体不存在: " + mediaId);
        }
        deleteStoredObjects(stored);
        if (!media.deleteMarked(mediaId)) {
            throw new IllegalStateException("媒体删除发生并发冲突");
        }
    }

    private StoredMedia requireVisible(long viewerId, String mediaId) {
        StoredMedia stored = media.find(mediaId)
                .orElseThrow(() -> new NotFoundException("媒体不存在: " + mediaId));
        if (!"READY".equals(stored.objectStatus())) {
            throw new NotFoundException("媒体尚未上传完成: " + mediaId);
        }
        if (stored.postId() == null) {
            if (stored.ownerId() != viewerId) {
                throw new ForbiddenException("无权访问未发布的媒体");
            }
        } else {
            permissions.requireVisible(viewerId, stored.postId());
        }
        return stored;
    }

    private ObjectView object(StoredMedia stored, Variant variant) {
        if (variant == Variant.PREVIEW) {
            if (!"READY".equals(stored.previewStatus()) || stored.previewStorageKey() == null) {
                throw new NotFoundException("媒体预览尚未生成: " + stored.id());
            }
            return new ObjectView(stored.previewStorageKey(), stored.previewContentType(),
                    stored.originalFilename() + ".preview.jpg", stored.previewSizeBytes());
        }
        return new ObjectView(stored.storageKey(), stored.contentType(),
                stored.originalFilename(), stored.sizeBytes());
    }

    private MediaAttachment attachment(StoredMedia value) {
        return new MediaAttachment(value.id(), value.mediaType(), value.contentType(), value.originalFilename(),
                value.sizeBytes(), "/api/media/" + value.id() + "/content",
                "READY".equals(value.previewStatus()) ? "/api/media/" + value.id() + "/preview" : null,
                value.previewStatus(), value.createdAt());
    }

    private void validate(String filename, String rawContentType, long sizeBytes, boolean empty) {
        if (empty) {
            throw new BadRequestException("上传文件不能为空");
        }
        if (sizeBytes > maxBytes) {
            throw new BadRequestException("文件超过大小限制: " + maxBytes + " bytes");
        }
        if (!ALLOWED_TYPES.containsKey(normalizeType(rawContentType))) {
            throw new BadRequestException("仅支持 JPEG、PNG、GIF、WebP、MP4、WebM 和 MOV");
        }
        safeFilename(filename);
    }

    private String normalizeType(String value) {
        return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String safeFilename(String original) {
        String value = original == null || original.isBlank() ? "upload" : Path.of(original).getFileName().toString();
        return value.length() <= 255 ? value : value.substring(value.length() - 255);
    }

    private String originalKey(long ownerId, String id) {
        return "originals/" + ownerId + "/" + id;
    }

    private void requireOwnerAndUnattached(long ownerId, StoredMedia stored) {
        if (stored.ownerId() != ownerId || stored.postId() != null) {
            throw new ForbiddenException("只能操作自己尚未发布的媒体");
        }
    }

    private void deleteStoredObjects(StoredMedia stored) {
        MediaStorage storage = storages.require(stored.storageProvider());
        storage.delete(stored.previewStorageKey());
        storage.delete(stored.storageKey());
    }

    private void tryDelete(MediaStorage storage, String key) {
        if (key == null) {
            return;
        }
        try {
            storage.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Failed to delete media object {}/{}", storage.provider(), key, exception);
        }
    }

    private void deleteOnRollback(MediaStorage storage, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    tryDelete(storage, key);
                }
            }
        });
    }

    private enum Variant {
        ORIGINAL, PREVIEW;

        private static Variant parse(String value) {
            try {
                return value == null ? ORIGINAL : valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new BadRequestException("媒体版本仅支持 ORIGINAL 或 PREVIEW");
            }
        }
    }

    private record ObjectView(String key, String contentType, String filename, long sizeBytes) {
    }

    public record InitiateUploadRequest(String filename, String contentType, long sizeBytes) {
    }

    public record UploadTicket(String mediaId, String mode, String uploadUrl, String method,
                               Map<String, String> headers, Instant expiresAt) {
    }

    public record MediaAccess(String url, Instant expiresAt, String variant) {
    }

    public record MediaContent(Resource resource, String contentType, String filename, long sizeBytes) {
    }
}
