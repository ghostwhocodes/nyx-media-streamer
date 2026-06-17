package com.nyx.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetricsServiceTest {
    private PrometheusMeterRegistry registry;
    private MetricsService service;

    @BeforeEach
    void setup() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        service = new MetricsService(registry);
    }

    @Test
    void scrapeReturnsPrometheusTextFormat() {
        String output = service.scrape();
        assertNotNull(output);
        assertTrue(!output.isBlank());
    }

    @Test
    void countersAndGaugesUpdateAsExpected() {
        double beforeSubmitted = service.getTranscodeSubmitted().count();
        service.getTranscodeSubmitted().increment();
        assertEquals(beforeSubmitted + 1.0, service.getTranscodeSubmitted().count());

        service.getTranscodeCompleted().increment();
        service.getTranscodeFailed().increment();
        service.getThumbnailCacheHits().increment();
        service.getThumbnailCacheHits().increment();
        service.getThumbnailCacheMisses().increment();

        assertEquals(1.0, service.getTranscodeCompleted().count());
        assertEquals(1.0, service.getTranscodeFailed().count());
        assertEquals(2.0, service.getThumbnailCacheHits().count());
        assertEquals(1.0, service.getThumbnailCacheMisses().count());

        service.jobStarted();
        service.jobStarted();
        assertEquals(2.0, registry.find("nyx_transcode_jobs_active").gauge().value());
        service.jobFinished();
        assertEquals(1.0, registry.find("nyx_transcode_jobs_active").gauge().value());
    }

    @Test
    void scrapeContainsRegisteredMetricNames() {
        service.getTranscodeSubmitted().increment();
        service.getThumbnailCacheHits().increment();
        String output = service.scrape();
        assertTrue(output.contains("nyx_transcode_jobs_submitted_total"));
        assertTrue(output.contains("nyx_thumbnail_cache_hits_total"));
        assertTrue(output.contains("nyx_transcode_jobs_active"));
    }

    @Test
    void allCoreCountersAreRegisteredInTheSameRegistry() {
        assertEquals(registry, service.getRegistry());
        String output = service.scrape();
        assertTrue(output.contains("nyx_transcode_jobs_submitted_total"));
        assertTrue(output.contains("nyx_transcode_jobs_completed_total"));
        assertTrue(output.contains("nyx_transcode_jobs_failed_total"));
        assertTrue(output.contains("nyx_thumbnail_cache_hits_total"));
        assertTrue(output.contains("nyx_thumbnail_cache_misses_total"));
    }

    @Test
    void ffmpegProcessAndDurationMetricsWork() {
        service.ffmpegProcessStarted();
        service.ffmpegProcessStarted();
        assertEquals(2, service.activeFfmpegProcesses.get());
        service.ffmpegProcessFinished();
        assertEquals(1, service.activeFfmpegProcesses.get());

        service.recordJobDuration(1_000_000_000L);
        service.recordJobDuration(2_000_000_000L);
        assertEquals(2, service.getJobDurationTimer().count());

        service.recordProbeDuration(500_000_000L);
        assertEquals(1, service.getProbeDurationTimer().count());

        service.recordFfmpegProcessDuration(2_000_000_000L, true);
        service.recordFfmpegProcessDuration(1_000_000_000L, false);
        assertEquals(2, service.getFfmpegProcessDurationTimer().count());
        assertEquals(1.0, service.getFfmpegProcessFailures().count());

        service.recordThumbnailGenerationDuration(300_000_000L);
        assertEquals(1, service.getThumbnailGenerationDurationTimer().count());
    }

    @Test
    void cacheGaugeSuppliersAreUsed() {
        service.setSegmentCacheSizeSupplier(() -> 42);
        assertEquals(42, service.getSegmentCacheSizeSupplier().get());
        assertEquals(42.0, registry.find("nyx_segment_cache_size").gauge().value());

        service.setProbeCacheSizeSupplier(() -> 17);
        assertEquals(17, service.getProbeCacheSizeSupplier().get());
        assertEquals(17.0, registry.find("nyx_probe_cache_size").gauge().value());
    }

    @Test
    void backupAndWebhookMetricsAccumulateCorrectly() {
        service.backupCompleted();
        service.backupFailed();
        service.webhookDispatched();
        service.webhookDispatched();
        service.webhookDeliverySuccess();
        service.webhookDeliveryFailure();
        service.webhookDeliveriesPurged(5);
        service.webhookDeliveriesPurged(3);

        assertEquals(1.0, service.getBackupSuccessCounter().count());
        assertEquals(1.0, service.getBackupFailureCounter().count());
        assertEquals(2.0, service.getWebhookDispatchedCounter().count());
        assertEquals(1.0, service.getWebhookDeliverySuccessCounter().count());
        assertEquals(1.0, service.getWebhookDeliveryFailureCounter().count());
        assertEquals(8.0, service.getWebhookDeliveriesPurgedCounter().count());

        String output = service.scrape();
        assertTrue(output.contains("nyx_backup_success_total"));
        assertTrue(output.contains("nyx_backup_failure_total"));
        assertTrue(output.contains("nyx_webhook_dispatched_total"));
        assertTrue(output.contains("nyx_webhook_delivery_success_total"));
        assertTrue(output.contains("nyx_webhook_delivery_failure_total"));
        assertTrue(output.contains("nyx_webhook_deliveries_purged_total"));
    }

    @Test
    void diskSpaceAndBackupGaugesRegister() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-metrics-disk-test");
        try {
            service.registerDiskSpaceGauges(List.of(tempDir), tempDir);
            assertNotNull(registry.find("nyx_disk_free_bytes").gauge());
            assertNotNull(registry.find("nyx_db_dir_free_bytes").gauge());

            Path dbDir = tempDir.resolve("db");
            Files.createDirectories(dbDir);
            HikariDataSource db = new HikariDataSource(new HikariConfig() {{
                setJdbcUrl("jdbc:sqlite:" + dbDir.resolve("t.db"));
                setDriverClassName("org.sqlite.JDBC");
                setMaximumPoolSize(1);
            }});
            try {
                db.getConnection().createStatement().execute("CREATE TABLE IF NOT EXISTS x (id INTEGER PRIMARY KEY)");
                BackupService backupService = AdminFixtures.newBackupService(
                    java.util.Map.of("t", db),
                    AdminFixtures.testBackupConfig(true, "", 0, 5),
                    dbDir
                );
                service.registerBackupGauges(backupService);
                assertNotNull(registry.find("nyx_backup_last_success_timestamp_seconds").gauge());
                assertNotNull(registry.find("nyx_backup_last_size_bytes").gauge());
            } finally {
                db.close();
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new IllegalStateException("Failed to delete " + path, exception);
                    }
                });
        }
    }
}
