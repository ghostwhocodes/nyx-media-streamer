package com.nyx.common.storage;

public record StorageEntryInfo(String key, long sizeBytes, long lastModifiedEpochMillis) {
    public String getKey() {
        return key;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getLastModifiedEpochMillis() {
        return lastModifiedEpochMillis;
    }
}
