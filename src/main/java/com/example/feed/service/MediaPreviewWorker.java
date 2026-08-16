package com.example.feed.service;

import com.example.feed.repository.MediaRepository;
import com.example.feed.repository.MediaRepository.StoredMedia;
import com.example.feed.service.storage.MediaStorage;
import com.example.feed.service.storage.MediaStorageRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class MediaPreviewWorker {
    private static final Logger log = LoggerFactory.getLogger(MediaPreviewWorker.class);

    private final MediaRepository media;
    private final MediaStorageRegistry storages;
    private final TransactionTemplate transaction;
    private final int width;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration processingTimeout;
    private final String ffmpegPath;
    private final Duration ffmpegTimeout;

    public MediaPreviewWorker(MediaRepository media, MediaStorageRegistry storages,
                              TransactionTemplate transaction,
                              @Value("${feed.media.preview.width:1280}") int width,
                              @Value("${feed.media.preview.batch-size:10}") int batchSize,
                              @Value("${feed.media.preview.max-attempts:3}") int maxAttempts,
                              @Value("${feed.media.preview.processing-timeout:10m}") Duration processingTimeout,
                              @Value("${feed.media.preview.ffmpeg-path:ffmpeg}") String ffmpegPath,
                              @Value("${feed.media.preview.ffmpeg-timeout:2m}") Duration ffmpegTimeout) {
        this.media = media;
        this.storages = storages;
        this.transaction = transaction;
        this.width = width;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.processingTimeout = processingTimeout;
        this.ffmpegPath = ffmpegPath;
        this.ffmpegTimeout = ffmpegTimeout;
    }

    @Scheduled(initialDelayString = "${feed.media.preview.initial-delay-ms:10000}",
            fixedDelayString = "${feed.media.preview.delay-ms:2000}")
    public void run() {
        Integer recovered = transaction.execute(status ->
                media.recoverTimedOutPreviews(Instant.now().minus(processingTimeout), maxAttempts));
        if (recovered != null && recovered > 0) {
            log.warn("Recovered {} timed out media preview jobs", recovered);
        }
        for (int index = 0; index < batchSize; index++) {
            String processorId = UUID.randomUUID().toString();
            StoredMedia item = transaction.execute(status -> media.claimNextPreview(processorId).orElse(null));
            if (item == null) {
                return;
            }
            process(item, processorId);
        }
    }

    private void process(StoredMedia item, String processorId) {
        MediaStorage storage = storages.require(item.storageProvider());
        String previewKey = "previews/" + item.ownerId() + "/" + item.id() + ".jpg";
        Path directory = null;
        try {
            directory = Files.createTempDirectory("feed-media-preview-");
            Path input = directory.resolve("source");
            Path output = directory.resolve("preview.jpg");
            Path processLog = directory.resolve("ffmpeg.log");
            try (InputStream content = storage.read(item.storageKey())) {
                Files.copy(content, input);
            }
            List<String> command = List.of(ffmpegPath, "-y", "-i", input.toString(),
                    "-frames:v", "1", "-vf", "scale='min(" + width + ",iw)':-2",
                    "-q:v", "3", output.toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(processLog.toFile()).start();
            if (!process.waitFor(ffmpegTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("ffmpeg 处理超时");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                String detail = Files.exists(processLog) ? Files.readString(processLog) : "";
                throw new IllegalStateException("ffmpeg 处理失败: " + tail(detail, 700));
            }
            long size = Files.size(output);
            try (InputStream content = Files.newInputStream(output)) {
                storage.put(previewKey, content, size, "image/jpeg");
            }
            Boolean saved = transaction.execute(status -> media.markPreviewReady(
                    item.id(), processorId, previewKey, "image/jpeg", size));
            if (!Boolean.TRUE.equals(saved)) {
                storage.delete(previewKey);
            }
        } catch (Exception exception) {
            log.warn("Media preview failed for {}", item.id(), exception);
            transaction.executeWithoutResult(status -> media.markPreviewFailure(
                    item.id(), processorId, exception.getMessage(), maxAttempts));
        } finally {
            deleteTemp(directory);
        }
    }

    private void deleteTemp(Path directory) {
        if (directory == null) {
            return;
        }
        for (String name : List.of("source", "preview.jpg", "ffmpeg.log")) {
            try {
                Files.deleteIfExists(directory.resolve(name));
            } catch (Exception ignored) {
                // Best-effort temporary file cleanup.
            }
        }
        try {
            Files.deleteIfExists(directory);
        } catch (Exception ignored) {
            // Best-effort temporary directory cleanup.
        }
    }

    private String tail(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(value.length() - maxLength);
    }
}
