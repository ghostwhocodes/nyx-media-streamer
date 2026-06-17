package com.nyx.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.MetricsPluginConfig;
import com.nyx.common.QuotaUsage;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigValueCoverageTest {

    @Test
    void configRecordsPreserveDefaultsCopiesAndAccessors() {
        Map<String, String> presets = new HashMap<>(Map.of("custom", "h264_custom"));
        Map<String, String> users = new HashMap<>(Map.of("alice", "secret"));
        Map<String, String> tokens = new HashMap<>(Map.of("api", "token"));
        List<Integer> thumbnailSizes = new ArrayList<>(List.of(120, 240));
        Set<String> allowedHosts = new HashSet<>(Set.of("example.test"));
        List<String> corsOrigins = new ArrayList<>(List.of("https://app.example.test"));
        List<MediaRootConfig> mediaRoots = new ArrayList<>(List.of(new MediaRootConfig(Path.of("/srv/media"))));

        FfmpegConfig ffmpeg = new FfmpegConfig("ffmpeg", "ffprobe", "7.0", 2, 3, 4, presets, null, 750L);
        FfmpegConfig ffmpegDefaults = new FfmpegConfig("ffmpeg", "ffprobe", "7.0", 2);
        TranscodeConfig transcode = new TranscodeConfig("dash", 15, 6);
        AudioConfig audio = new AudioConfig(null, null, null, 45L);
        AudioConfig defaultAudio = new AudioConfig();
        AuthConfig auth = new AuthConfig(true, null, users, tokens);
        AuthConfig defaultAuth = new AuthConfig();
        BackupConfig backup = new BackupConfig(true, null, 30, 7);
        BackupConfig defaultBackup = new BackupConfig();
        DatabaseConfig database = new DatabaseConfig(Path.of("/data/db"));
        DatabaseConfig pooledDatabase = new DatabaseConfig(Path.of("/data/db-pool"), 8);
        ThumbnailConfig thumbnails = new ThumbnailConfig(thumbnailSizes, 15, 512L, 30);
        ThumbnailConfig defaultThumbnails = new ThumbnailConfig();
        TlsConfig tls = new TlsConfig(true, null, null, null, null, 9443);
        TlsConfig defaultTls = new TlsConfig();
        WebhookConfig webhooks = new WebhookConfig(true, 5, 6_000L, 40_000L, 12, allowedHosts, 9, 90);
        WebhookConfig defaultWebhooks = new WebhookConfig();
        ServerConfig server = new ServerConfig(
            "0.0.0.0",
            8080,
            corsOrigins,
            mediaRoots,
            ffmpeg,
            transcode,
            database,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        ServerConfig convenienceServer = new ServerConfig(
            "127.0.0.1",
            9090,
            List.of("*"),
            List.of(new MediaRootConfig(Path.of("/srv/other"))),
            ffmpegDefaults,
            transcode,
            database
        );
        QuotaUsage usage = new QuotaUsage("user-1", 1, 2, 3, 4);
        UserQuotaOverride quotaOverride = new UserQuotaOverride(3, 120, 4_096L);
        UserQuotaOverride defaultQuotaOverride = new UserQuotaOverride();

        presets.put("ignored", "later");
        users.put("bob", "secret2");
        tokens.put("admin", "token2");
        thumbnailSizes.add(480);
        allowedHosts.add("mutated.test");
        corsOrigins.add("https://mutated.example.test");
        mediaRoots.add(new MediaRootConfig(Path.of("/srv/mutated")));

        assertEquals("ffmpeg", ffmpeg.getPath());
        assertEquals("ffprobe", ffmpeg.getFfprobePath());
        assertEquals("7.0", ffmpeg.getMinVersion());
        assertEquals(2, ffmpeg.getMaxConcurrentJobs());
        assertEquals(3, ffmpeg.getMaxConcurrentMediaProcesses());
        assertEquals("polling", ffmpeg.getWatchStrategy());
        assertEquals(Map.of("custom", "h264_custom"), ffmpeg.getQualityPresets());
        assertEquals(750L, ffmpeg.getSegmentWatchPollIntervalMs());
        assertEquals(4, ffmpeg.getMaxQueuedJobs());
        assertEquals(FfmpegConfig.DEFAULT_QUALITY_PRESETS, ffmpegDefaults.getQualityPresets());

        assertEquals("dash", transcode.getDefaultFormat());
        assertEquals(15, transcode.getSegmentCacheGracePeriodMinutes());
        assertEquals(6, transcode.getSegmentDurationSteadyStateSecs());
        assertEquals(10_000, transcode.getSegmentCacheMaxEntries());
        assertEquals(524_288_000L, transcode.getMinFreeDiskBytes());
        assertEquals(3, transcode.getMaxRetries());
        assertEquals(2_000L, transcode.getRetryBackoffMs());
        assertEquals(5, transcode.getCircuitBreakerThreshold());

        assertEquals("256k", audio.getAacBitrate());
        assertEquals("128k", audio.getOpusBitrate());
        assertEquals("192k", audio.getMp3Bitrate());
        assertEquals(45L, audio.getProcessTimeoutSeconds());
        assertEquals("256k", defaultAudio.getAacBitrate());

        assertEquals("", auth.getToken());
        assertEquals(Map.of("alice", "secret"), auth.getUsers());
        assertEquals(Map.of("api", "token"), auth.getTokens());
        assertEquals("", defaultAuth.getToken());

        assertTrue(backup.getEnabled());
        assertEquals("", backup.getDir());
        assertEquals(30, backup.getScheduleIntervalMinutes());
        assertEquals(7, backup.getRetainCount());
        assertEquals("", defaultBackup.getDir());

        assertEquals(Path.of("/data/db"), database.getDir());
        assertEquals(4, database.getPoolSize());
        assertEquals(600_000L, database.getIdleTimeoutMs());
        assertEquals(1_800_000L, database.getMaxLifetimeMs());
        assertEquals(8, pooledDatabase.getPoolSize());

        assertEquals(List.of(120, 240), thumbnails.getSizes());
        assertEquals(15, thumbnails.getVideoOffsetPercent());
        assertEquals(512L, thumbnails.getMaxCacheSizeMB());
        assertEquals(30, thumbnails.getCleanupIntervalMinutes());
        assertEquals(List.of(150, 300, 600), defaultThumbnails.getSizes());

        assertEquals("", tls.getKeystorePath());
        assertEquals("", tls.getKeystorePassword());
        assertEquals("nyx", tls.getKeyAlias());
        assertEquals("", tls.getKeyPassword());
        assertEquals(9443, tls.getPort());
        assertEquals(8443, defaultTls.getPort());

        assertTrue(webhooks.getEnabled());
        assertEquals(5, webhooks.getMaxRetries());
        assertEquals(6_000L, webhooks.getRetryBackoffMs());
        assertEquals(40_000L, webhooks.getTimeoutMs());
        assertEquals(12, webhooks.getMaxConcurrentDeliveries());
        assertEquals(Set.of("example.test"), webhooks.getAllowedHosts());
        assertEquals(9, webhooks.getDeliveryRetentionDays());
        assertEquals(90, webhooks.getCleanupIntervalMinutes());
        assertTrue(defaultWebhooks.getAllowedHosts().isEmpty());

        assertEquals(3, quotaOverride.getMaxConcurrentJobs());
        assertEquals(120, quotaOverride.getMaxRequestsPerMinute());
        assertEquals(4_096L, quotaOverride.getMaxStorageBytes());
        assertEquals(null, defaultQuotaOverride.getMaxConcurrentJobs());
        assertEquals(null, defaultQuotaOverride.getMaxRequestsPerMinute());
        assertEquals(null, defaultQuotaOverride.getMaxStorageBytes());

        assertEquals("0.0.0.0", server.getHost());
        assertEquals(8080, server.getPort());
        assertEquals(List.of("https://app.example.test"), server.getCorsOrigins());
        assertEquals(List.of(new MediaRootConfig(Path.of("/srv/media"))), server.getMediaRoots());
        assertSame(ffmpeg, server.getFfmpeg());
        assertSame(transcode, server.getTranscode());
        assertSame(database, server.getDatabase());
        assertNotNull(server.getThumbnails());
        assertNotNull(server.getAudio());
        assertNotNull(server.getAuth());
        assertNotNull(server.getRateLimit());
        assertNotNull(server.getCsrf());
        assertNotNull(server.getTls());
        assertNotNull(server.getWebhooks());
        assertNotNull(server.getQuota());
        assertNotNull(server.getBackup());
        assertNotNull(server.getStorage());
        assertNotNull(server.getCompatibility());
        assertFalse(server.getCompatibility().getQloud().getEnabled());
        assertEquals("0.0.0.0", server.getCompatibility().getQloud().getHost());
        assertEquals(8081, server.getCompatibility().getQloud().getPort());
        assertEquals("127.0.0.1", convenienceServer.getHost());
        assertEquals(9090, convenienceServer.getPort());
        assertFalse(convenienceServer.getCompatibility().getQloud().getEnabled());
        assertEquals("127.0.0.1", convenienceServer.getCompatibility().getQloud().getHost());
        assertEquals(9091, convenienceServer.getCompatibility().getQloud().getPort());

        assertEquals("user-1", usage.userId());
        assertEquals(0L, usage.storageUsedBytes());
        assertEquals(0L, usage.maxStorageBytes());

        assertThrows(UnsupportedOperationException.class, () -> server.getCorsOrigins().add("https://fail.example.test"));
        assertThrows(UnsupportedOperationException.class, () -> server.getMediaRoots().add(new MediaRootConfig(Path.of("/srv/fail"))));
        assertThrows(UnsupportedOperationException.class, () -> ffmpeg.getQualityPresets().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> auth.getUsers().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> thumbnails.getSizes().add(999));
        assertThrows(UnsupportedOperationException.class, () -> webhooks.getAllowedHosts().add("x"));
    }

    @Test
    void metricsPluginConfigStoresAndReturnsRegistry() {
        MetricsPluginConfig config = new MetricsPluginConfig();
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            config.setRegistry(registry);
            assertSame(registry, config.getRegistry());
        } finally {
            registry.close();
        }
    }
}
