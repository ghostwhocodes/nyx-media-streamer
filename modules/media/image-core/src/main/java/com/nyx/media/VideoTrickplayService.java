package com.nyx.media;

import com.nyx.common.ErrorCode;
import com.nyx.common.HealthMonitor;
import com.nyx.common.MediaTypes;
import com.nyx.common.NyxException;
import com.nyx.common.storage.StorageBackend;
import com.nyx.common.storage.StorageCacheHelper;
import com.nyx.ffmpeg.VideoTrickplayAssetOutput;
import com.nyx.ffmpeg.VideoTrickplayAssetPlan;
import com.nyx.ffmpeg.VideoTrickplayGenerator;
import com.nyx.ffmpeg.VideoTrickplayPlan;
import com.nyx.ffmpeg.VideoTrickplayRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VideoTrickplayService {
    private static final String CACHE_PREFIX = "video-trickplay";

    private final VideoTrickplayGenerator trickplayGenerator;
    private final long maxCacheSizeBytes;
    private final HealthMonitor healthService;
    private final StorageBackend storageBackend;
    private final Logger log = LoggerFactory.getLogger(VideoTrickplayService.class);
    @SuppressWarnings("unused")
    private final ScheduledFuture<?> cleanupTask;

    public VideoTrickplayService(VideoTrickplayGenerator trickplayGenerator, StorageBackend storageBackend) {
        this(trickplayGenerator, 1024L * 1024 * 1024, null, 60, null, storageBackend);
    }

    public VideoTrickplayService(
        VideoTrickplayGenerator trickplayGenerator,
        HealthMonitor healthService,
        StorageBackend storageBackend
    ) {
        this(trickplayGenerator, 1024L * 1024 * 1024, null, 60, healthService, storageBackend);
    }

    public VideoTrickplayService(
        VideoTrickplayGenerator trickplayGenerator,
        long maxCacheSizeBytes,
        ScheduledExecutorService cleanupScheduler,
        int cleanupIntervalMinutes,
        HealthMonitor healthService,
        StorageBackend storageBackend
    ) {
        this.trickplayGenerator = trickplayGenerator;
        this.maxCacheSizeBytes = maxCacheSizeBytes;
        this.healthService = healthService;
        this.storageBackend = storageBackend;
        this.cleanupTask = cleanupScheduler == null ? null : scheduleCleanup(cleanupScheduler, cleanupIntervalMinutes);
    }

    public VideoTrickplayPlan getPlan(Path sourcePath, VideoTrickplayRequest request) {
        ensureVideoSource(sourcePath);
        try {
            return trickplayGenerator.plan(sourcePath, request);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof NyxException nyxException) {
                return sneakyThrow(nyxException);
            }
            return sneakyThrow(new NyxException(
                ErrorCode.TRANSCODE_ERROR,
                exception.getMessage() == null ? "Failed to build video trickplay plan" : exception.getMessage(),
                exception
            ));
        }
    }

    public VideoTrickplayAssetOutput getAsset(Path sourcePath, VideoTrickplayRequest request, int assetIndex) {
        VideoTrickplayPlan plan = getPlan(sourcePath, request);
        VideoTrickplayAssetPlan assetPlan = findAsset(plan, assetIndex);
        return getAsset(sourcePath, plan, assetPlan.assetIndex());
    }

    public VideoTrickplayAssetOutput getAsset(Path sourcePath, VideoTrickplayPlan plan, int assetIndex) {
        ensureVideoSource(sourcePath);
        VideoTrickplayAssetPlan assetPlan = findAsset(plan, assetIndex);
        String storageKey = storageKeyFor(sourcePath, plan, assetPlan.getCacheKey(), assetPlan.outputExtension());

        var cacheResult = StorageCacheHelper.getOrGenerate(
            storageBackend,
            storageKey,
            sourcePath,
            assetPlan.outputMimeType(),
            () -> {
                try {
                    return trickplayGenerator.generate(sourcePath, assetPlan);
                } catch (Exception exception) {
                    if (exception instanceof NyxException nyxException) {
                        return sneakyThrow(nyxException);
                    }
                    return sneakyThrow(new NyxException(
                        ErrorCode.TRANSCODE_ERROR,
                        exception.getMessage() == null ? "Failed to generate video trickplay asset" : exception.getMessage(),
                        exception
                    ));
                }
            }
        );

        return new VideoTrickplayAssetOutput(cacheResult.getData(), assetPlan);
    }

    public VideoTrickplayResult getTrickplay(Path sourcePath, VideoTrickplayRequest request) {
        VideoTrickplayPlan plan = getPlan(sourcePath, request);
        List<VideoTrickplayAssetOutput> assets = new ArrayList<>(plan.assets().size());
        for (VideoTrickplayAssetPlan assetPlan : plan.assets()) {
            assets.add(getAsset(sourcePath, plan, assetPlan.assetIndex()));
        }
        return new VideoTrickplayResult(plan, assets);
    }

    public void cleanupStorageCache() {
        StorageCacheHelper.cleanupLRU(storageBackend, CACHE_PREFIX, maxCacheSizeBytes, log);
    }

    public void purgeCache() {
        storageBackend.deletePrefix(CACHE_PREFIX);
    }

    private ScheduledFuture<?> scheduleCleanup(ScheduledExecutorService scheduler, int cleanupIntervalMinutes) {
        Runnable task = () -> {
            try {
                cleanupStorageCache();
            } catch (Exception exception) {
                log.warn("Video trickplay cache cleanup failed: {}", exception.getMessage());
            }
        };
        if (cleanupIntervalMinutes > 0) {
            long intervalMs = cleanupIntervalMinutes * 60_000L;
            return scheduler.scheduleWithFixedDelay(task, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
        return scheduler.schedule(task, 0L, TimeUnit.MILLISECONDS);
    }

    private void ensureVideoSource(Path sourcePath) {
        if (healthService != null && !healthService.isFfmpegAvailable()) {
            sneakyThrow(new NyxException(ErrorCode.FFMPEG_UNAVAILABLE, "FFmpeg is not available"));
        }
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("Source file does not exist: " + sourcePath);
        }

        String mimeType = MediaTypes.detectMimeType(sourcePath);
        if (!MediaTypes.isVideo(mimeType)) {
            throw new IllegalArgumentException("Source file is not a video: " + sourcePath);
        }
    }

    private VideoTrickplayAssetPlan findAsset(VideoTrickplayPlan plan, int assetIndex) {
        for (VideoTrickplayAssetPlan assetPlan : plan.assets()) {
            if (assetPlan.assetIndex() == assetIndex) {
                return assetPlan;
            }
        }
        throw new IllegalArgumentException("No trickplay asset with index " + assetIndex);
    }

    private String storageKeyFor(
        Path sourcePath,
        VideoTrickplayPlan plan,
        String assetCacheKey,
        String outputExtension
    ) {
        String sourceHash = StorageCacheHelper.hashPath(sourcePath.toAbsolutePath().toString());
        String planHash = StorageCacheHelper.hashPath(plan.getCacheKey());
        String assetHash = StorageCacheHelper.hashPath(assetCacheKey);
        return CACHE_PREFIX + "/" + sourceHash + "/" + planHash + "/" + assetHash + "." + outputExtension;
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
