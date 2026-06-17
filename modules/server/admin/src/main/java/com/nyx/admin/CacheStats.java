package com.nyx.admin;

public record CacheStats(
    String storageBackend,
    int segmentCacheEntries
) {
}
