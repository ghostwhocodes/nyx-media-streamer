package com.nyx.common;

public interface MetricsCollector {
    void jobStarted();

    void jobFinished();

    void ffmpegProcessStarted();

    void ffmpegProcessFinished();

    void recordSegmentCacheEviction();

    void recordJobDuration(long nanos);

    void recordProbeCacheHit();

    void recordProbeCacheMiss();

    void recordProbeDuration(long nanos);

    void recordFfmpegProcessDuration(long nanos, boolean success);

    void recordThumbnailCacheHit();

    void recordThumbnailCacheMiss();

    void recordThumbnailGenerationDuration(long nanos);

    void webhookDispatched();

    void webhookDeliverySuccess();

    void webhookDeliveryFailure();

    void webhookDeliveriesPurged(int count);

    void backupCompleted();

    void backupFailed();
}
