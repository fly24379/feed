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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class MediaCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(MediaCleanupJob.class);

    private final MediaRepository media;
    private final MediaStorageRegistry storages;
    private final TransactionTemplate transaction;
    private final Duration unattachedRetention;
    private final int batchSize;

    public MediaCleanupJob(MediaRepository media, MediaStorageRegistry storages,
                           TransactionTemplate transaction,
                           @Value("${feed.media.unattached-retention:24h}") Duration unattachedRetention,
                           @Value("${feed.media.cleanup-batch-size:100}") int batchSize) {
        this.media = media;
        this.storages = storages;
        this.transaction = transaction;
        this.unattachedRetention = unattachedRetention;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelayString = "${feed.media.cleanup-delay-ms:3600000}",
            fixedDelayString = "${feed.media.cleanup-delay-ms:3600000}")
    public void run() {
        List<StoredMedia> candidates = transaction.execute(status ->
                media.findCleanupCandidates(Instant.now().minus(unattachedRetention), batchSize));
        if (candidates == null) {
            return;
        }
        int cleaned = 0;
        for (StoredMedia item : candidates) {
            if (!"DELETING".equals(item.objectStatus())) {
                Boolean claimed = transaction.execute(status -> media.markCleanupDeleting(item.id()));
                if (!Boolean.TRUE.equals(claimed)) {
                    continue;
                }
            }
            MediaStorage storage = storages.require(item.storageProvider());
            try {
                delete(storage, item.previewStorageKey());
                delete(storage, item.storageKey());
            } catch (RuntimeException exception) {
                log.error("Media cleanup will retry after object deletion failed: {}/{}",
                        storage.provider(), item.id(), exception);
                continue;
            }
            Boolean deleted = transaction.execute(status -> media.deleteMarked(item.id()));
            if (Boolean.TRUE.equals(deleted)) {
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.info("Cleaned {} unattached or expired media objects", cleaned);
        }
    }

    private void delete(MediaStorage storage, String key) {
        if (key == null) {
            return;
        }
        storage.delete(key);
    }
}
