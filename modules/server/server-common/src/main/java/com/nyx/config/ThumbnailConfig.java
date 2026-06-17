package com.nyx.config;

import java.util.List;

public record ThumbnailConfig(
    List<Integer> sizes,
    int videoOffsetPercent,
    long maxCacheSizeMB,
    int cleanupIntervalMinutes
) {
    public ThumbnailConfig {
        sizes = sizes == null ? List.of(150, 300, 600) : List.copyOf(sizes);
    }

    public ThumbnailConfig() {
        this(List.of(150, 300, 600), 10, 1024L, 60);
    }

    public List<Integer> getSizes() {
        return sizes;
    }

    public int getVideoOffsetPercent() {
        return videoOffsetPercent;
    }

    public long getMaxCacheSizeMB() {
        return maxCacheSizeMB;
    }

    public int getCleanupIntervalMinutes() {
        return cleanupIntervalMinutes;
    }
}
