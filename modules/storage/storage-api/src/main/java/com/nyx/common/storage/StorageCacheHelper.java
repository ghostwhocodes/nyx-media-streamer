package com.nyx.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

public final class StorageCacheHelper {
    private static final Logger LOG = LoggerFactory.getLogger(StorageCacheHelper.class);

    private StorageCacheHelper() {
    }

    public static CacheResult getOrGenerate(
        StorageBackend backend,
        String storageKey,
        Path sourcePath,
        Supplier<byte[]> generate
    ) {
        return getOrGenerate(backend, storageKey, sourcePath, null, generate);
    }

    public static CacheResult getOrGenerate(
        StorageBackend backend,
        String storageKey,
        Path sourcePath,
        String contentType,
        Supplier<byte[]> generate
    ) {
        String sourceSize;
        String sourceMtime;
        try {
            sourceSize = Long.toString(Files.size(sourcePath));
            sourceMtime = Long.toString(Files.getLastModifiedTime(sourcePath).toMillis());
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to inspect source path: " + sourcePath, failure);
        }

        try {
            StorageMetadata metadata = backend.metadata(storageKey);
            if (metadata != null) {
                String cachedSourceSize = metadata.userMetadata().get("sourceSize");
                String cachedSourceMtime = metadata.userMetadata().get("sourceMtime");
                if (sourceSize.equals(cachedSourceSize) && sourceMtime.equals(cachedSourceMtime)) {
                    byte[] cached = backend.read(storageKey);
                    if (cached != null) {
                        return new CacheResult(cached, true);
                    }
                    LOG.debug("Cache entry vanished for {}, regenerating", storageKey);
                }
                LOG.debug("Storage cache invalidated for {}", storageKey);
            }
        } catch (Exception failure) {
            LOG.warn("Storage backend read failed for {}, regenerating: {}", storageKey, failure.getMessage());
        }

        byte[] data = generate.get();
        try {
            backend.write(storageKey, data, contentType, java.util.Map.of(
                "sourceSize", sourceSize,
                "sourceMtime", sourceMtime
            ));
        } catch (Exception failure) {
            LOG.warn("Storage backend write failed for {}, returning generated data: {}", storageKey, failure.getMessage());
        }
        return new CacheResult(data, false);
    }

    public static String hashPath(String path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(path.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to hash path", failure);
        }
    }

    public static void cleanupLRU(StorageBackend backend, String prefix, long maxBytes, Logger logger) {
        long total = backend.totalSize(prefix);
        if (total <= maxBytes) {
            return;
        }

        long targetSize = (maxBytes * 80) / 100;
        List<StorageEntryInfo> entries = backend.listWithMetadata(prefix).stream()
            .sorted(Comparator.comparingLong(StorageEntryInfo::lastModifiedEpochMillis))
            .toList();

        long currentSize = total;
        for (StorageEntryInfo entry : entries) {
            if (currentSize <= targetSize) {
                break;
            }
            try {
                backend.delete(entry.key());
                currentSize -= entry.sizeBytes();
            } catch (Exception failure) {
                logger.warn("Failed to delete cache entry {}: {}", entry.key(), failure.getMessage());
            }
        }

        logger.info("Storage cache cleanup ({}): reduced to {} MB", prefix, currentSize / (1024 * 1024));
    }

    public record CacheResult(byte[] data, boolean cacheHit) {
        public byte[] getData() {
            return data;
        }

        public boolean isCacheHit() {
            return cacheHit;
        }
    }
}
