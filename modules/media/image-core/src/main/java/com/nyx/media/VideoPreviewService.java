package com.nyx.media;

import com.nyx.common.ErrorCode;
import com.nyx.common.HealthMonitor;
import com.nyx.common.MediaTypes;
import com.nyx.common.NyxException;
import com.nyx.common.storage.StorageBackend;
import com.nyx.common.storage.StorageCacheHelper;
import com.nyx.ffmpeg.VideoPreviewGenerator;
import com.nyx.ffmpeg.VideoPreviewOutput;
import com.nyx.ffmpeg.VideoPreviewPlan;
import com.nyx.ffmpeg.VideoPreviewRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VideoPreviewService {
    private final VideoPreviewGenerator previewGenerator;
    private final long maxCacheSizeBytes;
    private final HealthMonitor healthService;
    private final StorageBackend storageBackend;
    private final Logger log = LoggerFactory.getLogger(VideoPreviewService.class);
    @SuppressWarnings("unused")
    private final ScheduledFuture<?> cleanupTask;

    public VideoPreviewService(VideoPreviewGenerator previewGenerator, StorageBackend storageBackend) {
        this(previewGenerator, 1024L * 1024 * 1024, null, 60, null, storageBackend);
    }

    public VideoPreviewService(
        VideoPreviewGenerator previewGenerator,
        HealthMonitor healthService,
        StorageBackend storageBackend
    ) {
        this(previewGenerator, 1024L * 1024 * 1024, null, 60, healthService, storageBackend);
    }

    public VideoPreviewService(
        VideoPreviewGenerator previewGenerator,
        long maxCacheSizeBytes,
        ScheduledExecutorService cleanupScheduler,
        int cleanupIntervalMinutes,
        HealthMonitor healthService,
        StorageBackend storageBackend
    ) {
        this.previewGenerator = previewGenerator;
        this.maxCacheSizeBytes = maxCacheSizeBytes;
        this.healthService = healthService;
        this.storageBackend = storageBackend;
        this.cleanupTask = cleanupScheduler == null ? null : scheduleCleanup(cleanupScheduler, cleanupIntervalMinutes);
    }

    public VideoPreviewOutput getPreview(Path sourcePath, VideoPreviewRequest request) {
        if (healthService != null && !healthService.isFfmpegAvailable()) {
            return sneakyThrow(new NyxException(ErrorCode.FFMPEG_UNAVAILABLE, "FFmpeg is not available"));
        }
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("Source file does not exist: " + sourcePath);
        }

        String mimeType = MediaTypes.detectMimeType(sourcePath);
        if (!MediaTypes.isVideo(mimeType)) {
            throw new IllegalArgumentException("Source file is not a video: " + sourcePath);
        }

        VideoPreviewPlan plan;
        try {
            plan = previewGenerator.plan(sourcePath, request);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof NyxException nyxException) {
                return sneakyThrow(nyxException);
            }
            return sneakyThrow(new NyxException(
                ErrorCode.TRANSCODE_ERROR,
                exception.getMessage() == null ? "Failed to build video preview" : exception.getMessage(),
                exception
            ));
        }

        String hash = StorageCacheHelper.hashPath(sourcePath.toAbsolutePath().toString());
        String storageKey = "video-previews/" + hash + "/" + plan.getCacheKey() + "." + plan.outputExtension();

        var cacheResult = StorageCacheHelper.getOrGenerate(
            storageBackend,
            storageKey,
            sourcePath,
            plan.outputMimeType(),
            () -> {
                try {
                    return previewGenerator.generate(sourcePath, plan);
                } catch (Exception exception) {
                    if (exception instanceof NyxException nyxException) {
                        return sneakyThrow(nyxException);
                    }
                    return sneakyThrow(new NyxException(
                        ErrorCode.TRANSCODE_ERROR,
                        exception.getMessage() == null ? "Failed to generate video preview" : exception.getMessage(),
                        exception
                    ));
                }
            }
        );

        return new VideoPreviewOutput(cacheResult.getData(), plan);
    }

    public void cleanupStorageCache() {
        StorageCacheHelper.cleanupLRU(storageBackend, "video-previews", maxCacheSizeBytes, log);
    }

    public void purgeCache() {
        storageBackend.deletePrefix("video-previews");
    }

    private ScheduledFuture<?> scheduleCleanup(ScheduledExecutorService scheduler, int cleanupIntervalMinutes) {
        Runnable task = () -> {
            try {
                cleanupStorageCache();
            } catch (Exception exception) {
                log.warn("Video preview cache cleanup failed: {}", exception.getMessage());
            }
        };
        if (cleanupIntervalMinutes > 0) {
            long intervalMs = cleanupIntervalMinutes * 60_000L;
            return scheduler.scheduleWithFixedDelay(task, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
        return scheduler.schedule(task, 0L, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
