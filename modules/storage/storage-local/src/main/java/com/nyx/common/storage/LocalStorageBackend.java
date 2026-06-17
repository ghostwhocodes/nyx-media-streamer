package com.nyx.common.storage;

import com.nyx.json.NyxJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class LocalStorageBackend implements StorageBackend {
    private static final Logger LOG = LoggerFactory.getLogger(LocalStorageBackend.class);

    private final Path baseDir;
    private final com.fasterxml.jackson.databind.ObjectMapper json = NyxJson.newMapper();
    private final Path normalizedBase;

    public LocalStorageBackend(Path baseDir) {
        this.baseDir = baseDir;
        this.normalizedBase = baseDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to create local storage base directory", exception);
        }
    }

    @Override
    public byte[] read(String key) {
        Path path = resolve(key);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to read local storage object " + key, exception);
        }
    }

    @Override
    public void write(String key, byte[] data, String contentType, Map<String, String> metadata) {
        Path path = resolve(key);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, data);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to write local storage object " + key, exception);
        }
        writeMetaSidecar(key, metadata, contentType);
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public boolean delete(String key) {
        Path path = resolve(key);
        Path metaPath = metaPath(key);
        try {
            Files.deleteIfExists(metaPath);
            return Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to delete local storage object " + key, exception);
        }
    }

    @Override
    public StorageMetadata metadata(String key) {
        Path path = resolve(key);
        if (!Files.exists(path)) {
            return null;
        }

        SidecarMeta sidecar = readMetaSidecar(key);
        try {
            return new StorageMetadata(
                Files.size(path),
                Files.getLastModifiedTime(path).toMillis(),
                sidecar.contentType(),
                sidecar.userMetadata()
            );
        } catch (IOException exception) {
            throw new RuntimeException("Failed to read metadata for local storage object " + key, exception);
        }
    }

    @Override
    public List<String> list(String prefix) {
        Path dir = resolve(prefix);
        if (!Files.exists(dir)) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> !path.getFileName().toString().endsWith(".meta"))
                .forEach(path -> result.add(normalizedBase.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')));
        } catch (IOException exception) {
            throw new RuntimeException("Failed to list local storage prefix " + prefix, exception);
        }
        return result;
    }

    @Override
    public int deletePrefix(String prefix) {
        Path dir = resolve(prefix);
        if (!Files.exists(dir)) {
            return 0;
        }

        List<Path> paths;
        try (Stream<Path> stream = Files.walk(dir)) {
            paths = stream.toList();
        } catch (IOException exception) {
            throw new RuntimeException("Failed to walk local storage prefix " + prefix, exception);
        }

        int count = 0;
        for (int index = paths.size() - 1; index >= 0; index--) {
            Path path = paths.get(index);
            try {
                if (Files.isRegularFile(path) && !path.getFileName().toString().endsWith(".meta")) {
                    count++;
                }
                Files.deleteIfExists(path);
            } catch (Exception exception) {
                LOG.warn("Failed to delete {}: {}", path, exception.getMessage());
            }
        }
        return count;
    }

    @Override
    public long totalSize(String prefix) {
        Path dir = resolve(prefix);
        if (!Files.exists(dir)) {
            return 0L;
        }

        long total = 0L;
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path file : stream.filter(Files::isRegularFile)
                .filter(path -> !path.getFileName().toString().endsWith(".meta"))
                .toList()) {
                try {
                    total += Files.size(file);
                } catch (Exception exception) {
                    LOG.warn("Cannot read file size for {}: {}", file, exception.getMessage());
                }
            }
        } catch (IOException exception) {
            throw new RuntimeException("Failed to sum local storage prefix " + prefix, exception);
        }
        return total;
    }

    @Override
    public void close() {
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage key must not be blank");
        }
        Path resolved = normalizedBase.resolve(key).normalize();
        if (!resolved.startsWith(normalizedBase)) {
            throw new IllegalArgumentException("Storage key escapes base directory: " + key);
        }
        return resolved;
    }

    private Path metaPath(String key) {
        return resolve(key + ".meta");
    }

    private void writeMetaSidecar(String key, Map<String, String> metadata, String contentType) {
        Path metaPath = metaPath(key);
        SidecarMeta sidecar = new SidecarMeta(contentType, metadata == null ? Map.of() : metadata);
        if (sidecar.contentType() == null && sidecar.userMetadata().isEmpty()) {
            try {
                Files.deleteIfExists(metaPath);
            } catch (IOException exception) {
                throw new RuntimeException("Failed to delete local storage metadata sidecar for " + key, exception);
            }
            return;
        }
        try {
            Files.createDirectories(metaPath.getParent());
            Files.writeString(metaPath, json.writeValueAsString(sidecar));
        } catch (IOException exception) {
            throw new RuntimeException("Failed to write local storage metadata sidecar for " + key, exception);
        }
    }

    private SidecarMeta readMetaSidecar(String key) {
        Path metaPath = metaPath(key);
        if (!Files.exists(metaPath)) {
            return new SidecarMeta(null, Map.of());
        }
        try {
            return json.readValue(Files.readString(metaPath), SidecarMeta.class);
        } catch (Exception ignored) {
            return new SidecarMeta(null, Map.of());
        }
    }

    record SidecarMeta(String contentType, Map<String, String> userMetadata) {
        SidecarMeta {
            userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
        }
    }
}
