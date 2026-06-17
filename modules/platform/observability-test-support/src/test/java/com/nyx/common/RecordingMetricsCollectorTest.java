package com.nyx.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingMetricsCollectorTest {
    @Test
    void recordsMetricEvents() {
        RecordingMetricsCollector collector = new RecordingMetricsCollector();

        collector.jobStarted();
        collector.jobFinished();
        collector.ffmpegProcessStarted();
        collector.ffmpegProcessFinished();
        collector.recordSegmentCacheEviction();
        collector.recordJobDuration(10);
        collector.recordProbeCacheHit();
        collector.recordProbeCacheMiss();
        collector.recordProbeDuration(20);
        collector.recordFfmpegProcessDuration(30, false);
        collector.recordThumbnailCacheHit();
        collector.recordThumbnailCacheMiss();
        collector.recordThumbnailGenerationDuration(40);
        collector.webhookDispatched();
        collector.webhookDeliverySuccess();
        collector.webhookDeliveryFailure();
        collector.webhookDeliveriesPurged(3);
        collector.backupCompleted();
        collector.backupFailed();

        assertThat(collector.getJobsStarted()).isEqualTo(1);
        assertThat(collector.getJobsFinished()).isEqualTo(1);
        assertThat(collector.getFfmpegStarted()).isEqualTo(1);
        assertThat(collector.getFfmpegFinished()).isEqualTo(1);
        assertThat(collector.getSegmentEvictions()).isEqualTo(1);
        assertThat(collector.getJobDurations()).isEqualTo(List.of(10L));
        assertThat(collector.getProbeCacheHits()).isEqualTo(1);
        assertThat(collector.getProbeCacheMisses()).isEqualTo(1);
        assertThat(collector.getProbeDurations()).isEqualTo(List.of(20L));
        assertThat(collector.getFfmpegDurations()).isEqualTo(List.of(30L));
        assertThat(collector.getFfmpegFailures()).isEqualTo(1);
        assertThat(collector.getThumbnailCacheHits()).isEqualTo(1);
        assertThat(collector.getThumbnailCacheMisses()).isEqualTo(1);
        assertThat(collector.getThumbnailDurations()).isEqualTo(List.of(40L));
        assertThat(collector.getWebhookDispatched()).isEqualTo(1);
        assertThat(collector.getWebhookSuccess()).isEqualTo(1);
        assertThat(collector.getWebhookFailure()).isEqualTo(1);
        assertThat(collector.getWebhookPurged()).isEqualTo(3);
        assertThat(collector.getBackupCompleted()).isEqualTo(1);
        assertThat(collector.getBackupFailed()).isEqualTo(1);
    }
}
