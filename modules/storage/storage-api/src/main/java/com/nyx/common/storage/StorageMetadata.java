package com.nyx.common.storage;

import java.util.Map;

public record StorageMetadata(
    long sizeBytes,
    long lastModifiedEpochMillis,
    String contentType,
    Map<String, String> userMetadata
) {
    public StorageMetadata {
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
    }

    public StorageMetadata(long sizeBytes, long lastModifiedEpochMillis) {
        this(sizeBytes, lastModifiedEpochMillis, null, Map.of());
    }

    public StorageMetadata(long sizeBytes, long lastModifiedEpochMillis, String contentType) {
        this(sizeBytes, lastModifiedEpochMillis, contentType, Map.of());
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getLastModifiedEpochMillis() {
        return lastModifiedEpochMillis;
    }

    public String getContentType() {
        return contentType;
    }

    public Map<String, String> getUserMetadata() {
        return userMetadata;
    }
}
