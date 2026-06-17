package com.nyx.media;

import com.nyx.common.storage.StorageBackend;
import com.nyx.common.storage.StorageCacheHelper;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StrippedImageCache {
    private final ExifExtractor exifExtractor;
    private final long maxCacheSizeBytes;
    private final StorageBackend storageBackend;
    private final Logger log = LoggerFactory.getLogger(StrippedImageCache.class);
    @SuppressWarnings("unused")
    private final ScheduledFuture<?> cleanupTask;

    public StrippedImageCache(ExifExtractor exifExtractor, StorageBackend storageBackend) {
        this(exifExtractor, 1024L * 1024 * 1024, null, 60, storageBackend);
    }

    public StrippedImageCache(
        ExifExtractor exifExtractor,
        long maxCacheSizeBytes,
        ScheduledExecutorService cleanupScheduler,
        int cleanupIntervalMinutes,
        StorageBackend storageBackend
    ) {
        this.exifExtractor = exifExtractor;
        this.maxCacheSizeBytes = maxCacheSizeBytes;
        this.storageBackend = storageBackend;
        this.cleanupTask = cleanupScheduler == null ? null : scheduleCleanup(cleanupScheduler, cleanupIntervalMinutes);
    }

    public byte[] getStrippedImage(Path sourcePath) {
        String hash = StorageCacheHelper.hashPath(sourcePath.toAbsolutePath().toString());
        String extension = extension(sourcePath);
        String storageKey = "stripped/" + hash + "/stripped." + extension;

        var cacheResult = StorageCacheHelper.getOrGenerate(
            storageBackend,
            storageKey,
            sourcePath,
            () -> exifExtractor.stripSensitiveExif(sourcePath)
        );

        return cacheResult.getData();
    }

    public void cleanupStorageCache() {
        StorageCacheHelper.cleanupLRU(storageBackend, "stripped", maxCacheSizeBytes, log);
    }

    private ScheduledFuture<?> scheduleCleanup(ScheduledExecutorService scheduler, int cleanupIntervalMinutes) {
        Runnable task = () -> {
            try {
                cleanupStorageCache();
            } catch (Exception exception) {
                log.warn("Stripped image cache cleanup failed: {}", exception.getMessage());
            }
        };
        if (cleanupIntervalMinutes > 0) {
            long intervalMs = cleanupIntervalMinutes * 60_000L;
            return scheduler.scheduleWithFixedDelay(task, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
        return scheduler.schedule(task, 0L, TimeUnit.MILLISECONDS);
    }

    private static String extension(Path path) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(dotIndex + 1) : "";
    }
}
