package com.example.feed.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Component
public class LocalMediaStorage implements MediaStorage {
    private final Path root;

    @Autowired
    public LocalMediaStorage(@Value("${feed.media.storage-path:./data/media}") String storagePath) {
        this(Path.of(storagePath));
    }

    LocalMediaStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建媒体存储目录: " + this.root, exception);
        }
    }

    @Override
    public String provider() {
        return "LOCAL";
    }

    @Override
    public void put(String key, InputStream content, long sizeBytes, String contentType) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public InputStream read(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public Optional<ObjectMetadata> head(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ObjectMetadata(Files.size(path), Files.probeContentType(path)));
        } catch (IOException exception) {
            throw new IllegalStateException("读取本地媒体元数据失败: " + key, exception);
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw new IllegalStateException("删除本地媒体失败: " + key, exception);
        }
    }

    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法媒体存储路径");
        }
        return resolved;
    }
}
