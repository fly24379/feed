package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.api.ForbiddenException;
import com.example.feed.api.NotFoundException;
import com.example.feed.domain.MediaAttachment;
import com.example.feed.repository.MediaRepository;
import com.example.feed.repository.MediaRepository.StoredMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final Path storageRoot;
    private final long maxBytes;

    public MediaService(MediaRepository media, PermissionService permissions,
                        @Value("${feed.media.storage-path:./data/media}") String storagePath,
                        @Value("${feed.media.max-file-size:20MB}") DataSize maxFileSize) {
        this.media = media;
        this.permissions = permissions;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
        this.maxBytes = maxFileSize.toBytes();
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建媒体存储目录: " + storageRoot, exception);
        }
    }

    @Transactional
    public MediaAttachment upload(long ownerId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new BadRequestException("上传文件不能为空");
        }
        if (file.getSize() > maxBytes) {
            throw new BadRequestException("文件超过大小限制: " + maxBytes + " bytes");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String mediaType = ALLOWED_TYPES.get(contentType);
        if (mediaType == null) {
            throw new BadRequestException("仅支持 JPEG、PNG、GIF、WebP、MP4、WebM 和 MOV");
        }
        String id = UUID.randomUUID().toString();
        String storageKey = id;
        Path target = resolve(storageKey);
        String filename = safeFilename(file.getOriginalFilename());
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            deleteOnRollback(target);
            Instant createdAt = Instant.now();
            media.insert(new StoredMedia(id, ownerId, null, mediaType, contentType,
                    filename, storageKey, file.getSize(), createdAt));
            return new MediaAttachment(id, mediaType, contentType, filename, file.getSize(),
                    "/api/media/" + id + "/content", createdAt);
        } catch (Exception exception) {
            tryDelete(target);
            if (exception instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("保存媒体文件失败", exception);
        }
    }

    @Transactional(readOnly = true)
    public MediaContent content(long viewerId, String mediaId) {
        StoredMedia stored = media.find(mediaId)
                .orElseThrow(() -> new NotFoundException("媒体不存在: " + mediaId));
        if (stored.postId() == null) {
            if (stored.ownerId() != viewerId) {
                throw new ForbiddenException("无权访问未发布的媒体");
            }
        } else {
            permissions.requireVisible(viewerId, stored.postId());
        }
        Path path = resolve(stored.storageKey());
        if (!Files.isRegularFile(path)) {
            throw new NotFoundException("媒体文件不存在: " + mediaId);
        }
        return new MediaContent(new FileSystemResource(path), stored.contentType(),
                stored.originalFilename(), stored.sizeBytes());
    }

    @Transactional
    public void deleteUnattached(long ownerId, String mediaId) {
        StoredMedia stored = media.find(mediaId)
                .orElseThrow(() -> new NotFoundException("媒体不存在: " + mediaId));
        if (stored.ownerId() != ownerId || stored.postId() != null) {
            throw new ForbiddenException("只能删除自己尚未发布的媒体");
        }
        if (!media.deleteUnattached(mediaId, ownerId)) {
            throw new NotFoundException("媒体不存在: " + mediaId);
        }
        deleteAfterCommit(resolve(stored.storageKey()));
    }

    private Path resolve(String storageKey) {
        Path resolved = storageRoot.resolve(storageKey).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("非法媒体存储路径");
        }
        return resolved;
    }

    private String safeFilename(String original) {
        String value = original == null || original.isBlank() ? "upload" : Path.of(original).getFileName().toString();
        return value.length() <= 255 ? value : value.substring(value.length() - 255);
    }

    private void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Failed to delete media file {}", path, exception);
        }
    }

    private void deleteOnRollback(Path path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    tryDelete(path);
                }
            }
        });
    }

    private void deleteAfterCommit(Path path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            tryDelete(path);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tryDelete(path);
            }
        });
    }

    public record MediaContent(Resource resource, String contentType, String filename, long sizeBytes) {
    }
}
