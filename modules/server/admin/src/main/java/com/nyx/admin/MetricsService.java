package com.nyx.admin;

import com.nyx.common.MetricsCollector;
import com.nyx.ffmpeg.ProbeMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class MetricsService implements MetricsCollector, ProbeMetricsCollector {
    private final PrometheusMeterRegistry registry;
    private final AtomicInteger activeJobs = new AtomicInteger(0);
    final AtomicInteger activeFfmpegProcesses = new AtomicInteger(0);
    private Supplier<Integer> segmentCacheSizeSupplier = () -> 0;
    private Supplier<Integer> probeCacheSizeSupplier = () -> 0;

    private final Counter transcodeSubmitted;
    private final Counter transcodeCompleted;
    private final Counter transcodeFailed;
    private final Counter thumbnailCacheHits;
    private final Counter thumbnailCacheMisses;
    private final Timer jobDurationTimer;
    private final Counter segmentCacheEvictionsCounter;
    private final Counter probeCacheHits;
    private final Counter probeCacheMisses;
    private final Timer probeDurationTimer;
    private final Timer ffmpegProcessDurationTimer;
    private final Counter ffmpegProcessFailures;
    private final Timer thumbnailGenerationDurationTimer;
    private final Counter webhookDispatchedCounter;
    private final Counter webhookDeliverySuccessCounter;
    private final Counter webhookDeliveryFailureCounter;
    private final Counter webhookDeliveriesPurgedCounter;
    private final Counter backupSuccessCounter;
    private final Counter backupFailureCounter;

    public MetricsService(PrometheusMeterRegistry registry) {
        this.registry = registry;
        transcodeSubmitted = Counter.builder("nyx_transcode_jobs_submitted_total")
            .description("Total transcode jobs submitted")
            .register(registry);
        transcodeCompleted = Counter.builder("nyx_transcode_jobs_completed_total")
            .description("Total transcode jobs completed successfully")
            .register(registry);
        transcodeFailed = Counter.builder("nyx_transcode_jobs_failed_total")
            .description("Total transcode jobs failed")
            .register(registry);
        thumbnailCacheHits = Counter.builder("nyx_thumbnail_cache_hits_total")
            .description("Thumbnail cache hits")
            .register(registry);
        thumbnailCacheMisses = Counter.builder("nyx_thumbnail_cache_misses_total")
            .description("Thumbnail cache misses")
            .register(registry);
        jobDurationTimer = Timer.builder("nyx_transcode_job_duration_seconds")
            .description("Duration of transcode jobs from start to finish")
            .register(registry);
        segmentCacheEvictionsCounter = Counter.builder("nyx_segment_cache_evictions_total")
            .description("Total segment cache evictions")
            .register(registry);
        probeCacheHits = Counter.builder("nyx_probe_cache_hits_total")
            .description("Probe result cache hits")
            .register(registry);
        probeCacheMisses = Counter.builder("nyx_probe_cache_misses_total")
            .description("Probe result cache misses (actual ffprobe invocations)")
            .register(registry);
        probeDurationTimer = Timer.builder("nyx_probe_duration_seconds")
            .description("Duration of ffprobe invocations")
            .register(registry);
        ffmpegProcessDurationTimer = Timer.builder("nyx_ffmpeg_process_duration_seconds")
            .description("Duration of FFmpeg subprocess executions")
            .register(registry);
        ffmpegProcessFailures = Counter.builder("nyx_ffmpeg_process_failures_total")
            .description("Total FFmpeg subprocess failures")
            .register(registry);
        thumbnailGenerationDurationTimer = Timer.builder("nyx_thumbnail_generation_duration_seconds")
            .description("Duration of thumbnail generation via FFmpeg")
            .register(registry);
        webhookDispatchedCounter = Counter.builder("nyx_webhook_dispatched_total")
            .description("Total webhook deliveries dispatched")
            .register(registry);
        webhookDeliverySuccessCounter = Counter.builder("nyx_webhook_delivery_success_total")
            .description("Total successful webhook deliveries")
            .register(registry);
        webhookDeliveryFailureCounter = Counter.builder("nyx_webhook_delivery_failure_total")
            .description("Total failed webhook deliveries (all retries exhausted)")
            .register(registry);
        webhookDeliveriesPurgedCounter = Counter.builder("nyx_webhook_deliveries_purged_total")
            .description("Total webhook delivery records purged by cleanup")
            .register(registry);
        backupSuccessCounter = Counter.builder("nyx_backup_success_total")
            .description("Total successful database backups")
            .register(registry);
        backupFailureCounter = Counter.builder("nyx_backup_failure_total")
            .description("Total failed database backups")
            .register(registry);

        Gauge.builder("nyx_transcode_jobs_active", activeJobs, AtomicInteger::doubleValue)
            .description("Currently active transcode jobs")
            .register(registry);
        Gauge.builder("nyx_ffmpeg_processes_active", activeFfmpegProcesses, AtomicInteger::doubleValue)
            .description("Number of active FFmpeg subprocesses")
            .register(registry);
        Gauge.builder("nyx_segment_cache_size", () -> segmentCacheSizeSupplier.get().doubleValue())
            .description("Number of entries in the segment cache")
            .register(registry);
        Gauge.builder("nyx_probe_cache_size", () -> probeCacheSizeSupplier.get().doubleValue())
            .description("Number of entries in the probe result cache")
            .register(registry);
    }

    public PrometheusMeterRegistry getRegistry() {
        return registry;
    }

    public void setSegmentCacheSizeSupplier(Supplier<Integer> segmentCacheSizeSupplier) {
        this.segmentCacheSizeSupplier = segmentCacheSizeSupplier;
    }

    public Supplier<Integer> getSegmentCacheSizeSupplier() {
        return segmentCacheSizeSupplier;
    }

    public void setProbeCacheSizeSupplier(Supplier<Integer> probeCacheSizeSupplier) {
        this.probeCacheSizeSupplier = probeCacheSizeSupplier;
    }

    public Supplier<Integer> getProbeCacheSizeSupplier() {
        return probeCacheSizeSupplier;
    }

    public Counter getTranscodeSubmitted() {
        return transcodeSubmitted;
    }

    public Counter getTranscodeCompleted() {
        return transcodeCompleted;
    }

    public Counter getTranscodeFailed() {
        return transcodeFailed;
    }

    public Counter getThumbnailCacheHits() {
        return thumbnailCacheHits;
    }

    public Counter getThumbnailCacheMisses() {
        return thumbnailCacheMisses;
    }

    public Timer getJobDurationTimer() {
        return jobDurationTimer;
    }

    public Counter getSegmentCacheEvictionsCounter() {
        return segmentCacheEvictionsCounter;
    }

    public Counter getProbeCacheHits() {
        return probeCacheHits;
    }

    public Counter getProbeCacheMisses() {
        return probeCacheMisses;
    }

    public Timer getProbeDurationTimer() {
        return probeDurationTimer;
    }

    public Timer getFfmpegProcessDurationTimer() {
        return ffmpegProcessDurationTimer;
    }

    public Counter getFfmpegProcessFailures() {
        return ffmpegProcessFailures;
    }

    public Timer getThumbnailGenerationDurationTimer() {
        return thumbnailGenerationDurationTimer;
    }

    public Counter getWebhookDispatchedCounter() {
        return webhookDispatchedCounter;
    }

    public Counter getWebhookDeliverySuccessCounter() {
        return webhookDeliverySuccessCounter;
    }

    public Counter getWebhookDeliveryFailureCounter() {
        return webhookDeliveryFailureCounter;
    }

    public Counter getWebhookDeliveriesPurgedCounter() {
        return webhookDeliveriesPurgedCounter;
    }

    public Counter getBackupSuccessCounter() {
        return backupSuccessCounter;
    }

    public Counter getBackupFailureCounter() {
        return backupFailureCounter;
    }

    @Override
    public void jobStarted() {
        activeJobs.incrementAndGet();
    }

    @Override
    public void jobFinished() {
        activeJobs.decrementAndGet();
    }

    @Override
    public void ffmpegProcessStarted() {
        activeFfmpegProcesses.incrementAndGet();
    }

    @Override
    public void ffmpegProcessFinished() {
        activeFfmpegProcesses.decrementAndGet();
    }

    @Override
    public void recordSegmentCacheEviction() {
        segmentCacheEvictionsCounter.increment();
    }

    @Override
    public void recordJobDuration(long nanos) {
        jobDurationTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordProbeCacheHit() {
        probeCacheHits.increment();
    }

    @Override
    public void recordProbeCacheMiss() {
        probeCacheMisses.increment();
    }

    @Override
    public void recordProbeDuration(long nanos) {
        probeDurationTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordFfmpegProcessDuration(long nanos, boolean success) {
        ffmpegProcessDurationTimer.record(nanos, TimeUnit.NANOSECONDS);
        if (!success) {
            ffmpegProcessFailures.increment();
        }
    }

    @Override
    public void recordThumbnailCacheHit() {
        thumbnailCacheHits.increment();
    }

    @Override
    public void recordThumbnailCacheMiss() {
        thumbnailCacheMisses.increment();
    }

    @Override
    public void recordThumbnailGenerationDuration(long nanos) {
        thumbnailGenerationDurationTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void webhookDispatched() {
        webhookDispatchedCounter.increment();
    }

    @Override
    public void webhookDeliverySuccess() {
        webhookDeliverySuccessCounter.increment();
    }

    @Override
    public void webhookDeliveryFailure() {
        webhookDeliveryFailureCounter.increment();
    }

    @Override
    public void webhookDeliveriesPurged(int count) {
        webhookDeliveriesPurgedCounter.increment(count);
    }

    @Override
    public void backupCompleted() {
        backupSuccessCounter.increment();
    }

    @Override
    public void backupFailed() {
        backupFailureCounter.increment();
    }

    public void registerBackupGauges(BackupService backupService) {
        Gauge.builder(
                "nyx_backup_last_success_timestamp_seconds",
                () -> (double) backupService.getLastBackupTimestampEpochSeconds()
            )
            .description("Unix epoch seconds of last successful backup")
            .register(registry);

        Gauge.builder("nyx_backup_last_size_bytes", () -> (double) backupService.getLastBackupTotalBytes())
            .description("Total bytes of last successful backup")
            .register(registry);
    }

    public void registerDiskSpaceGauges(List<Path> mediaRoots, Path dbDir) {
        for (Path root : mediaRoots) {
            Gauge.builder(
                    "nyx_disk_free_bytes",
                    () -> {
                        try {
                            return Files.getFileStore(root).getUsableSpace() * 1.0d;
                        } catch (Exception ignored) {
                            return 0.0d;
                        }
                    }
                )
                .tag("root", root.toString())
                .description("Free disk space at media root")
                .register(registry);
        }
        Gauge.builder(
                "nyx_db_dir_free_bytes",
                () -> {
                    try {
                        return Files.getFileStore(dbDir).getUsableSpace() * 1.0d;
                    } catch (Exception ignored) {
                        return 0.0d;
                    }
                }
            )
            .description("Free disk space at database directory")
            .register(registry);
    }

    public String scrape() {
        return registry.scrape();
    }
}
