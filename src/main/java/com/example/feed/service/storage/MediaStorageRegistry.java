package com.example.feed.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class MediaStorageRegistry {
    private final Map<String, MediaStorage> storages;
    private final String defaultProvider;

    public MediaStorageRegistry(List<MediaStorage> storages,
                                @Value("${feed.media.storage-type:LOCAL}") String defaultProvider) {
        this.storages = storages.stream().collect(Collectors.toUnmodifiableMap(
                storage -> storage.provider().toUpperCase(Locale.ROOT), Function.identity()));
        this.defaultProvider = defaultProvider.toUpperCase(Locale.ROOT);
        require(this.defaultProvider);
    }

    public MediaStorage defaultStorage() {
        return require(defaultProvider);
    }

    public MediaStorage require(String provider) {
        MediaStorage storage = storages.get(provider.toUpperCase(Locale.ROOT));
        if (storage == null) {
            throw new IllegalStateException("媒体存储提供方未配置: " + provider);
        }
        return storage;
    }
}
