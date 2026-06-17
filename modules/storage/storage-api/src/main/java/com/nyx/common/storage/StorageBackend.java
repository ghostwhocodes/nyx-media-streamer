package com.nyx.common.storage;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface StorageBackend extends Closeable {
    byte[] read(String key);

    default void write(String key, byte[] data) {
        write(key, data, null, Map.of());
    }

    default void write(String key, byte[] data, String contentType) {
        write(key, data, contentType, Map.of());
    }

    default void write(String key, byte[] data, Map<String, String> metadata) {
        write(key, data, null, metadata);
    }

    void write(String key, byte[] data, String contentType, Map<String, String> metadata);

    boolean exists(String key);

    boolean delete(String key);

    StorageMetadata metadata(String key);

    List<String> list(String prefix);

    int deletePrefix(String prefix);

    long totalSize(String prefix);

    default List<StorageEntryInfo> listWithMetadata(String prefix) {
        List<StorageEntryInfo> result = new ArrayList<>();
        for (String key : list(prefix)) {
            StorageMetadata metadata = metadata(key);
            if (metadata != null) {
                result.add(new StorageEntryInfo(key, metadata.sizeBytes(), metadata.lastModifiedEpochMillis()));
            }
        }
        return result;
    }

    @Override
    void close();
}
