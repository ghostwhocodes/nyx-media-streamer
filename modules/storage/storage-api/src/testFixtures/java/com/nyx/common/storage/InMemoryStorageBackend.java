package com.nyx.common.storage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class InMemoryStorageBackend implements StorageBackend {
    private final ConcurrentHashMap<String, StoredObject> objects = new ConcurrentHashMap<>();

    public ConcurrentHashMap<String, StoredObject> getObjects() {
        return objects;
    }

    @Override
    public byte[] read(String key) {
        StoredObject object = objects.get(key);
        return object == null ? null : object.data();
    }

    @Override
    public void write(String key, byte[] data, String contentType, Map<String, String> metadata) {
        objects.put(key, new StoredObject(data, contentType, metadata, System.currentTimeMillis()));
    }

    @Override
    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    @Override
    public boolean delete(String key) {
        return objects.remove(key) != null;
    }

    @Override
    public StorageMetadata metadata(String key) {
        StoredObject object = objects.get(key);
        if (object == null) {
            return null;
        }
        return new StorageMetadata(
            object.data().length,
            object.createdAtMillis(),
            object.contentType(),
            object.metadata()
        );
    }

    @Override
    public List<String> list(String prefix) {
        return objects.keySet().stream()
            .filter(key -> key.startsWith(prefix))
            .collect(Collectors.toList());
    }

    @Override
    public int deletePrefix(String prefix) {
        List<String> keys = objects.keySet().stream()
            .filter(key -> key.startsWith(prefix))
            .toList();
        keys.forEach(objects::remove);
        return keys.size();
    }

    @Override
    public long totalSize(String prefix) {
        return objects.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(prefix))
            .mapToLong(entry -> entry.getValue().data().length)
            .sum();
    }

    @Override
    public void close() {
        // no resources to release
    }

    public record StoredObject(
        byte[] data,
        String contentType,
        Map<String, String> metadata,
        long createdAtMillis
    ) {
        public StoredObject(byte[] data, String contentType, Map<String, String> metadata) {
            this(data, contentType, metadata, System.currentTimeMillis());
        }

        public byte[] getData() {
            return data;
        }

        public String getContentType() {
            return contentType;
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }

        public long getCreatedAtMillis() {
            return createdAtMillis;
        }
    }
}
