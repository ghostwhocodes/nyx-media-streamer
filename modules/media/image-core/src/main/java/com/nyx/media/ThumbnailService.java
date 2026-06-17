package com.nyx.media;

import com.nyx.common.ErrorCode;
import com.nyx.common.HealthMonitor;
import com.nyx.common.MediaTypes;
import com.nyx.common.MetricsCollector;
import com.nyx.common.NyxException;
import com.nyx.common.storage.StorageBackend;
import com.nyx.common.storage.StorageCacheHelper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ThumbnailService {
    private static final Set<Integer> DEFAULT_ALLOWED_SIZES =
        Collections.unmodifiableSet(new LinkedHashSet<>(List.of(150, 300, 600)));

    private final Set<Integer> allowedSizes;
    private final String ffmpegPath;
    private final String ffprobePath;
    private final int videoOffsetPercent;
    private final long maxCacheSizeBytes;
    private final Semaphore ffmpegSemaphore;
    private final MetricsCollector metricsService;
    private final HealthMonitor healthService;
    private final StorageBackend storageBackend;
    private final Logger log = LoggerFactory.getLogger(ThumbnailService.class);
    private final int primaryThumbnailSize;
    @SuppressWarnings("unused")
    private final ScheduledFuture<?> cleanupTask;

    public ThumbnailService(StorageBackend storageBackend) {
        this(DEFAULT_ALLOWED_SIZES, "ffmpeg", "ffprobe", 10, 1024L * 1024 * 1024, null, null, 60, null, null, storageBackend);
    }

    public ThumbnailService(Set<Integer> allowedSizes, StorageBackend storageBackend) {
        this(allowedSizes, "ffmpeg", "ffprobe", 10, 1024L * 1024 * 1024, null, null, 60, null, null, storageBackend);
    }

    public ThumbnailService(Set<Integer> allowedSizes, HealthMonitor healthService, StorageBackend storageBackend) {
        this(allowedSizes, "ffmpeg", "ffprobe", 10, 1024L * 1024 * 1024, null, null, 60, null, healthService, storageBackend);
    }

    public ThumbnailService(long maxCacheSizeBytes, StorageBackend storageBackend) {
        this(DEFAULT_ALLOWED_SIZES, "ffmpeg", "ffprobe", 10, maxCacheSizeBytes, null, null, 60, null, null, storageBackend);
    }

    public ThumbnailService(
        ScheduledExecutorService cleanupScheduler,
        int cleanupIntervalMinutes,
        StorageBackend storageBackend
    ) {
        this(DEFAULT_ALLOWED_SIZES, "ffmpeg", "ffprobe", 10, 1024L * 1024 * 1024, null, cleanupScheduler, cleanupIntervalMinutes, null, null, storageBackend);
    }

    public ThumbnailService(
        Set<Integer> allowedSizes,
        String ffmpegPath,
        String ffprobePath,
        int videoOffsetPercent,
        long maxCacheSizeBytes,
        Semaphore ffmpegSemaphore,
        ScheduledExecutorService cleanupScheduler,
        int cleanupIntervalMinutes,
        MetricsCollector metricsService,
        HealthMonitor healthService,
        StorageBackend storageBackend
    ) {
        this.allowedSizes = Collections.unmodifiableSet(new LinkedHashSet<>(allowedSizes));
        this.ffmpegPath = ffmpegPath;
        this.ffprobePath = ffprobePath;
        this.videoOffsetPercent = videoOffsetPercent;
        this.maxCacheSizeBytes = maxCacheSizeBytes;
        this.ffmpegSemaphore = ffmpegSemaphore;
        this.metricsService = metricsService;
        this.healthService = healthService;
        this.storageBackend = storageBackend;
        this.primaryThumbnailSize = this.allowedSizes.iterator().next();
        this.cleanupTask = cleanupScheduler == null ? null : scheduleCleanup(cleanupScheduler, cleanupIntervalMinutes);
    }

    public int getPrimaryThumbnailSize() {
        return primaryThumbnailSize;
    }

    public byte[] getThumbnail(Path sourcePath, int size) {
        return getThumbnail(sourcePath, size, null);
    }

    public byte[] getThumbnail(Path sourcePath, int size, String storageKeyOverride) {
        if (healthService != null && !healthService.isFfmpegAvailable()) {
            return sneakyThrow(new NyxException(ErrorCode.FFMPEG_UNAVAILABLE, "FFmpeg is not available"));
        }
        if (!allowedSizes.contains(size)) {
            throw new IllegalArgumentException("Invalid thumbnail size: " + size + ". Allowed: " + allowedSizes);
        }
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("Source file does not exist: " + sourcePath);
        }

        String hash = StorageCacheHelper.hashPath(sourcePath.toAbsolutePath().toString());
        String storageKey = storageKeyOverride != null ? storageKeyOverride : "thumbnails/" + hash + "/" + size + ".jpg";

        var cacheResult = StorageCacheHelper.getOrGenerate(
            storageBackend,
            storageKey,
            sourcePath,
            "image/jpeg",
            () -> {
                long genStart = System.nanoTime();
                Path tempFile;
                try {
                    tempFile = Files.createTempFile("nyx-thumb-", ".jpg");
                } catch (IOException exception) {
                    return sneakyThrow(exception);
                }
                try {
                    generateThumbnail(sourcePath, tempFile, size);
                    if (metricsService != null) {
                        metricsService.recordThumbnailGenerationDuration(System.nanoTime() - genStart);
                    }
                    return Files.readAllBytes(tempFile);
                } catch (IOException exception) {
                    return sneakyThrow(exception);
                } finally {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {
                        log.debug("Failed to delete thumbnail temp file {}", tempFile);
                    }
                }
            }
        );

        if (cacheResult.isCacheHit()) {
            if (metricsService != null) {
                metricsService.recordThumbnailCacheHit();
            }
        } else if (metricsService != null) {
            metricsService.recordThumbnailCacheMiss();
        }

        return cacheResult.getData();
    }

    public void cleanupStorageCache() {
        StorageCacheHelper.cleanupLRU(storageBackend, "thumbnails", maxCacheSizeBytes, log);
    }

    public void purgeCache() {
        storageBackend.deletePrefix("thumbnails");
    }

    private ScheduledFuture<?> scheduleCleanup(ScheduledExecutorService scheduler, int cleanupIntervalMinutes) {
        Runnable task = () -> {
            try {
                cleanupStorageCache();
            } catch (Exception exception) {
                log.warn("Thumbnail cache cleanup failed: {}", exception.getMessage());
            }
        };
        if (cleanupIntervalMinutes > 0) {
            long intervalMs = cleanupIntervalMinutes * 60_000L;
            return scheduler.scheduleWithFixedDelay(task, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
        return scheduler.schedule(task, 0L, TimeUnit.MILLISECONDS);
    }

    private void generateThumbnail(Path sourcePath, Path outputPath, int size) {
        withSemaphore(() -> {
            String mimeType = MediaTypes.detectMimeType(sourcePath);
            List<String> command = MediaTypes.isVideo(mimeType)
                ? buildVideoThumbnailCommand(sourcePath, outputPath, size)
                : buildImageThumbnailCommand(sourcePath, outputPath, size);

            log.debug("Generating thumbnail: {}", String.join(" ", command));

            try {
                Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    log.error("FFmpeg thumbnail generation failed (exit {}): {}", exitCode, output);
                    return sneakyThrow(new IOException("Thumbnail generation failed for " + sourcePath + ": " + output));
                }
            } catch (IOException exception) {
                return sneakyThrow(exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return sneakyThrow(exception);
            }

            if (!Files.exists(outputPath)) {
                return sneakyThrow(new IOException("Thumbnail was not created: " + outputPath));
            }
            return null;
        });
    }

    private List<String> buildImageThumbnailCommand(Path source, Path output, int size) {
        return List.of(
            ffmpegPath,
            "-i", source.toString(),
            "-vf", "scale=" + size + ":-1",
            "-vframes", "1",
            "-y",
            output.toString()
        );
    }

    private List<String> buildVideoThumbnailCommand(Path source, Path output, int size) {
        double seekSeconds = getVideoSeekPosition(source);
        return List.of(
            ffmpegPath,
            "-ss", Double.toString(seekSeconds),
            "-i", source.toString(),
            "-vf", "scale=" + size + ":-1",
            "-vframes", "1",
            "-y",
            output.toString()
        );
    }

    private double getVideoSeekPosition(Path source) {
        List<String> command = List.of(
            ffprobePath,
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            source.toString()
        );

        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("ffprobe failed for {}, using 0s offset", source.getFileName());
                return 0.0;
            }

            Double duration = output.isEmpty() ? null : Double.valueOf(output);
            if (duration == null) {
                return 0.0;
            }
            return duration * videoOffsetPercent / 100.0;
        } catch (NumberFormatException ignored) {
            return 0.0;
        } catch (IOException exception) {
            return sneakyThrow(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return sneakyThrow(exception);
        }
    }

    private <T> T withSemaphore(SupplierWithResult<T> block) {
        if (ffmpegSemaphore == null) {
            return block.get();
        }

        try {
            ffmpegSemaphore.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return sneakyThrow(exception);
        }

        try {
            return block.get();
        } finally {
            ffmpegSemaphore.release();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    @FunctionalInterface
    private interface SupplierWithResult<T> {
        T get();
    }
}
