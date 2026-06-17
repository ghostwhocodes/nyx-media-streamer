package com.nyx.admin;

import com.nyx.common.MetricsCollector;
import com.nyx.config.AudioConfig;
import com.nyx.config.AuthConfig;
import com.nyx.config.BackupConfig;
import com.nyx.config.ConfigService;
import com.nyx.config.CsrfConfig;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.QuotaConfig;
import com.nyx.config.RateLimitConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.StorageConfig;
import com.nyx.config.ThumbnailConfig;
import com.nyx.config.TlsConfig;
import com.nyx.config.TranscodeConfig;
import com.nyx.config.UserQuotaOverride;
import com.nyx.config.WebhookConfig;
import com.nyx.http.Route;
import com.nyx.media.LibraryAdminService;
import com.nyx.media.LibraryScanService;
import com.nyx.media.ThumbnailService;
import com.nyx.transcode.contracts.SegmentCacheService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;

public final class AdminFixtures {
    private AdminFixtures() {
    }

    public static BackupConfig testBackupConfig() {
        return testBackupConfig(false, "", 0, 5);
    }

    public static BackupConfig testBackupConfig(boolean enabled, String dir, int scheduleIntervalMinutes, int retainCount) {
        return new BackupConfig(enabled, dir, scheduleIntervalMinutes, retainCount);
    }

    public static QuotaConfig testQuotaConfig() {
        return testQuotaConfig(false, 4, 60, 10_737_418_240L, Map.of());
    }

    public static QuotaConfig testQuotaConfig(
        boolean enabled,
        int defaultMaxConcurrentJobs,
        int defaultMaxRequestsPerMinute,
        long defaultMaxStorageBytes,
        Map<String, UserQuotaOverride> userOverrides
    ) {
        return new QuotaConfig(
            enabled,
            defaultMaxConcurrentJobs,
            defaultMaxRequestsPerMinute,
            defaultMaxStorageBytes,
            userOverrides
        );
    }

    public static AuthConfig testAuthConfig() {
        return testAuthConfig(false, "", Map.of(), Map.of());
    }

    public static AuthConfig testAuthConfig(
        boolean enabled,
        String token,
        Map<String, String> users,
        Map<String, String> tokens
    ) {
        return new AuthConfig(enabled, token, users, tokens);
    }

    public static TranscodeConfig testTranscodeConfig() {
        return testTranscodeConfig("both", 10, 6, 10_000, 524_288_000L, 3, 2_000L, 5);
    }

    public static TranscodeConfig testTranscodeConfig(
        String defaultFormat,
        int segmentCacheGracePeriodMinutes,
        int segmentDurationSteadyStateSecs,
        int segmentCacheMaxEntries,
        long minFreeDiskBytes,
        int maxRetries,
        long retryBackoffMs,
        int circuitBreakerThreshold
    ) {
        return new TranscodeConfig(
            defaultFormat,
            segmentCacheGracePeriodMinutes,
            segmentDurationSteadyStateSecs,
            segmentCacheMaxEntries,
            minFreeDiskBytes,
            maxRetries,
            retryBackoffMs,
            circuitBreakerThreshold
        );
    }

    public static ServerConfig testServerConfig() {
        return testServerConfig(
            "0.0.0.0",
            8080,
            List.of("*"),
            List.of(),
            new FfmpegConfig("ffmpeg", "ffprobe", "6.0", 2),
            testTranscodeConfig(),
            new DatabaseConfig(Path.of("/tmp")),
            new ThumbnailConfig(),
            new AudioConfig(),
            new AuthConfig(),
            new RateLimitConfig(),
            new CsrfConfig(),
            new TlsConfig(),
            new WebhookConfig(),
            new QuotaConfig(),
            new BackupConfig(),
            new StorageConfig()
        );
    }

    public static ServerConfig testServerConfig(List<MediaRootConfig> mediaRoots, DatabaseConfig database, AuthConfig auth) {
        ServerConfig base = testServerConfig();
        return testServerConfig(
            base.getHost(),
            base.getPort(),
            base.getCorsOrigins(),
            mediaRoots,
            base.getFfmpeg(),
            base.getTranscode(),
            database,
            base.getThumbnails(),
            base.getAudio(),
            auth,
            base.getRateLimit(),
            base.getCsrf(),
            base.getTls(),
            base.getWebhooks(),
            base.getQuota(),
            base.getBackup(),
            base.getStorage()
        );
    }

    public static ServerConfig testServerConfig(
        String host,
        int port,
        List<String> corsOrigins,
        List<MediaRootConfig> mediaRoots,
        FfmpegConfig ffmpeg,
        TranscodeConfig transcode,
        DatabaseConfig database,
        ThumbnailConfig thumbnails,
        AudioConfig audio,
        AuthConfig auth,
        RateLimitConfig rateLimit,
        CsrfConfig csrf,
        TlsConfig tls,
        WebhookConfig webhooks,
        QuotaConfig quota,
        BackupConfig backup,
        StorageConfig storage
    ) {
        return new ServerConfig(
            host,
            port,
            corsOrigins,
            mediaRoots,
            ffmpeg,
            transcode,
            database,
            thumbnails,
            audio,
            auth,
            rateLimit,
            csrf,
            tls,
            webhooks,
            quota,
            backup,
            storage
        );
    }

    public static ServerConfig copyServerConfig(
        ServerConfig config,
        String host,
        Integer port,
        List<String> corsOrigins,
        List<MediaRootConfig> mediaRoots,
        FfmpegConfig ffmpeg,
        TranscodeConfig transcode,
        DatabaseConfig database,
        ThumbnailConfig thumbnails,
        AudioConfig audio,
        AuthConfig auth,
        RateLimitConfig rateLimit,
        CsrfConfig csrf,
        TlsConfig tls,
        WebhookConfig webhooks,
        QuotaConfig quota,
        BackupConfig backup,
        StorageConfig storage
    ) {
        return testServerConfig(
            host == null ? config.getHost() : host,
            port == null ? config.getPort() : port,
            corsOrigins == null ? config.getCorsOrigins() : corsOrigins,
            mediaRoots == null ? config.getMediaRoots() : mediaRoots,
            ffmpeg == null ? config.getFfmpeg() : ffmpeg,
            transcode == null ? config.getTranscode() : transcode,
            database == null ? config.getDatabase() : database,
            thumbnails == null ? config.getThumbnails() : thumbnails,
            audio == null ? config.getAudio() : audio,
            auth == null ? config.getAuth() : auth,
            rateLimit == null ? config.getRateLimit() : rateLimit,
            csrf == null ? config.getCsrf() : csrf,
            tls == null ? config.getTls() : tls,
            webhooks == null ? config.getWebhooks() : webhooks,
            quota == null ? config.getQuota() : quota,
            backup == null ? config.getBackup() : backup,
            storage == null ? config.getStorage() : storage
        );
    }

    public static BackupService newBackupService(
        Map<String, DataSource> databases,
        BackupConfig config,
        Path databaseDir
    ) {
        return new BackupService(databases, config, databaseDir);
    }

    public static BackupService newBackupService(
        Map<String, DataSource> databases,
        BackupConfig config,
        Path databaseDir,
        MetricsCollector metricsCollector
    ) {
        return new BackupService(databases, config, databaseDir, metricsCollector);
    }

    public static BackupService newBackupService(
        Map<String, DataSource> databases,
        BackupConfig config,
        Path databaseDir,
        MetricsCollector metricsCollector,
        ScheduledExecutorService scheduledExecutor
    ) {
        return new BackupService(databases, config, databaseDir, metricsCollector, scheduledExecutor);
    }

    public static RuntimeHealthResponse newRuntimeHealthResponse(
        String status,
        boolean ffmpegAvailable,
        int activeJobs,
        boolean dbWritable,
        boolean diskSpaceWarning,
        boolean dbConnectivity,
        boolean stuckJobsWarning,
        boolean circuitBreakerOpen,
        String lastBackupTimestamp,
        Long lastBackupBytes
    ) {
        return new RuntimeHealthResponse(
            status,
            ffmpegAvailable,
            activeJobs,
            dbWritable,
            diskSpaceWarning,
            dbConnectivity,
            stuckJobsWarning,
            circuitBreakerOpen,
            lastBackupTimestamp,
            lastBackupBytes
        );
    }

    public static ReadinessResponse newReadinessResponse(
        String status,
        boolean dbWritable,
        boolean dbConnectivity,
        boolean diskSpaceOk,
        boolean circuitBreakerOpen
    ) {
        return new ReadinessResponse(status, dbWritable, dbConnectivity, diskSpaceOk, circuitBreakerOpen);
    }

    public static StartupHealthReport newStartupHealthReport(
        String ffmpegVersion,
        String ffprobeVersion,
        List<String> hwAccels,
        List<String> encoders,
        List<MediaRootStatus> mediaRoots,
        String dbStatus,
        JvmInfo jvmInfo
    ) {
        return new StartupHealthReport(ffmpegVersion, ffprobeVersion, hwAccels, encoders, mediaRoots, dbStatus, jvmInfo);
    }

    public static MediaRootStatus newMediaRootStatus(String path, boolean exists, boolean readable, long freeSpaceBytes) {
        return new MediaRootStatus(path, exists, readable, freeSpaceBytes);
    }

    public static JvmInfo newJvmInfo(String version, long maxMemoryBytes, int availableProcessors) {
        return new JvmInfo(version, maxMemoryBytes, availableProcessors);
    }

    public static void adminRoutes(
        Route route,
        ThumbnailService thumbnailService,
        SegmentCacheService segmentCache,
        Map<String, DataSource> databases,
        List<Path> mediaRoots,
        MetricsService metricsService,
        com.nyx.common.VirtualPathResolver virtualPathResolver,
        com.nyx.common.QuotaService quotaService,
        BackupService backupService,
        LibraryScanService libraryScanService,
        LibraryAdminService libraryAdminService,
        List<String> authProviders,
        String storageBackendType
    ) {
        AdminRoutes.adminRoutes(
            route,
            thumbnailService,
            segmentCache,
            databases,
            mediaRoots,
            metricsService,
            virtualPathResolver,
            quotaService,
            backupService,
            libraryScanService,
            libraryAdminService,
            authProviders,
            storageBackendType
        );
    }

    public static void configRoutes(Route route, ConfigService configService, List<String> authProviders) {
        ConfigRoutes.configRoutes(route, configService, authProviders);
    }
}
