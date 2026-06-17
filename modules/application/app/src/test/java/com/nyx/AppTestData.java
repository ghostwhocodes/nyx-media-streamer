package com.nyx;

import com.nyx.config.AudioConfig;
import com.nyx.config.AuthConfig;
import com.nyx.config.BackupConfig;
import com.nyx.config.CompatibilityConfig;
import com.nyx.config.CsrfConfig;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.QuotaConfig;
import com.nyx.config.QloudCompatibilityConfig;
import com.nyx.config.RateLimitConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.StorageConfig;
import com.nyx.config.ThumbnailConfig;
import com.nyx.config.TlsConfig;
import com.nyx.config.TranscodeConfig;
import com.nyx.config.WebhookConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class AppTestData {
    private AppTestData() {
    }

    static AuthConfig testAuthConfig() {
        return testAuthConfig(false, "", Map.of(), Map.of());
    }

    static AuthConfig testAuthConfig(
        boolean enabled,
        String token,
        Map<String, String> users,
        Map<String, String> tokens
    ) {
        return new AuthConfig(enabled, token, users, tokens);
    }

    static FfmpegConfig testFfmpegConfig() {
        return testFfmpegConfig(
            "ffmpeg",
            "ffprobe",
            "6.0",
            2,
            4,
            8,
            FfmpegConfig.DEFAULT_QUALITY_PRESETS,
            "polling",
            500L
        );
    }

    static FfmpegConfig testFfmpegConfig(
        String path,
        String ffprobePath,
        String minVersion,
        int maxConcurrentJobs,
        int maxConcurrentMediaProcesses,
        int maxQueuedJobs,
        Map<String, String> qualityPresets,
        String watchStrategy,
        long segmentWatchPollIntervalMs
    ) {
        return new FfmpegConfig(
            path,
            ffprobePath,
            minVersion,
            maxConcurrentJobs,
            maxConcurrentMediaProcesses,
            maxQueuedJobs,
            qualityPresets,
            watchStrategy,
            segmentWatchPollIntervalMs
        );
    }

    static TranscodeConfig testTranscodeConfig() {
        return testTranscodeConfig("both", 10, 6, 10_000, 524_288_000L, 3, 2_000L, 5);
    }

    static TranscodeConfig testTranscodeConfig(
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

    static ServerConfig testServerConfig() {
        return testServerConfig(
            "0.0.0.0",
            8080,
            List.of(),
            List.of(),
            testFfmpegConfig(),
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
            new StorageConfig(),
            disabledCompatibilityConfig()
        );
    }

    static ServerConfig testServerConfig(
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
        return testServerConfig(
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
            storage,
            disabledCompatibilityConfig()
        );
    }

    static ServerConfig testServerConfig(
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
        StorageConfig storage,
        CompatibilityConfig compatibility
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
            storage,
            compatibility
        );
    }

    static CompatibilityConfig disabledCompatibilityConfig() {
        return new CompatibilityConfig(new QloudCompatibilityConfig(false, null, QloudCompatibilityConfig.DEFAULT_PORT_SENTINEL));
    }
}
