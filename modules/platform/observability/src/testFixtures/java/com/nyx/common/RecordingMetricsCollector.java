package com.nyx.common;

import java.util.ArrayList;
import java.util.List;

public final class RecordingMetricsCollector implements MetricsCollector {
    private final List<Long> jobDurations = new ArrayList<>();
    private final List<Long> ffmpegDurations = new ArrayList<>();
    private final List<Long> probeDurations = new ArrayList<>();
    private final List<Long> thumbnailDurations = new ArrayList<>();

    private int jobsStarted;
    private int jobsFinished;
    private int ffmpegStarted;
    private int ffmpegFinished;
    private int segmentEvictions;
    private int probeCacheHits;
    private int probeCacheMisses;
    private int ffmpegFailures;
    private int thumbnailCacheHits;
    private int thumbnailCacheMisses;
    private int webhookDispatched;
    private int webhookSuccess;
    private int webhookFailure;
    private int webhookPurged;
    private int backupCompleted;
    private int backupFailed;

    @Override
    public void jobStarted() {
        jobsStarted++;
    }

    @Override
    public void jobFinished() {
        jobsFinished++;
    }

    @Override
    public void ffmpegProcessStarted() {
        ffmpegStarted++;
    }

    @Override
    public void ffmpegProcessFinished() {
        ffmpegFinished++;
    }

    @Override
    public void recordSegmentCacheEviction() {
        segmentEvictions++;
    }

    @Override
    public void recordJobDuration(long nanos) {
        jobDurations.add(nanos);
    }

    @Override
    public void recordProbeCacheHit() {
        probeCacheHits++;
    }

    @Override
    public void recordProbeCacheMiss() {
        probeCacheMisses++;
    }

    @Override
    public void recordProbeDuration(long nanos) {
        probeDurations.add(nanos);
    }

    @Override
    public void recordFfmpegProcessDuration(long nanos, boolean success) {
        ffmpegDurations.add(nanos);
        if (!success) {
            ffmpegFailures++;
        }
    }

    @Override
    public void recordThumbnailCacheHit() {
        thumbnailCacheHits++;
    }

    @Override
    public void recordThumbnailCacheMiss() {
        thumbnailCacheMisses++;
    }

    @Override
    public void recordThumbnailGenerationDuration(long nanos) {
        thumbnailDurations.add(nanos);
    }

    @Override
    public void webhookDispatched() {
        webhookDispatched++;
    }

    @Override
    public void webhookDeliverySuccess() {
        webhookSuccess++;
    }

    @Override
    public void webhookDeliveryFailure() {
        webhookFailure++;
    }

    @Override
    public void webhookDeliveriesPurged(int count) {
        webhookPurged += count;
    }

    @Override
    public void backupCompleted() {
        backupCompleted++;
    }

    @Override
    public void backupFailed() {
        backupFailed++;
    }

    public List<Long> getJobDurations() {
        return jobDurations;
    }

    public List<Long> getFfmpegDurations() {
        return ffmpegDurations;
    }

    public int getJobsStarted() {
        return jobsStarted;
    }

    public int getJobsFinished() {
        return jobsFinished;
    }

    public int getFfmpegStarted() {
        return ffmpegStarted;
    }

    public int getFfmpegFinished() {
        return ffmpegFinished;
    }

    public int getSegmentEvictions() {
        return segmentEvictions;
    }

    public int getProbeCacheHits() {
        return probeCacheHits;
    }

    public int getProbeCacheMisses() {
        return probeCacheMisses;
    }

    public List<Long> getProbeDurations() {
        return probeDurations;
    }

    public int getFfmpegFailures() {
        return ffmpegFailures;
    }

    public int getThumbnailCacheHits() {
        return thumbnailCacheHits;
    }

    public int getThumbnailCacheMisses() {
        return thumbnailCacheMisses;
    }

    public List<Long> getThumbnailDurations() {
        return thumbnailDurations;
    }

    public int getWebhookDispatched() {
        return webhookDispatched;
    }

    public int getWebhookSuccess() {
        return webhookSuccess;
    }

    public int getWebhookFailure() {
        return webhookFailure;
    }

    public int getWebhookPurged() {
        return webhookPurged;
    }

    public int getBackupCompleted() {
        return backupCompleted;
    }

    public int getBackupFailed() {
        return backupFailed;
    }
}
