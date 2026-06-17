package com.nyx.config;

import com.typesafe.config.ConfigFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServerConfigTest {
    @AfterEach
    void resetEnvironmentLookup() {
        ServerConfigLoader.resetEnvironmentLookupForTesting();
    }

    @Test
    void corsOriginsDefaultsToEmptyListForFailSecureCors() {
        ServerConfig serverConfig = load("application.conf");

        assertTrue(serverConfig.corsOrigins().isEmpty());
    }

    @Test
    void parsesApplicationConfCorrectly() {
        ServerConfig serverConfig = load("application.conf");

        assertEquals("0.0.0.0", serverConfig.host());
        assertEquals(8080, serverConfig.port());
        assertTrue(serverConfig.corsOrigins().isEmpty());

        assertFalse(serverConfig.mediaRoots().isEmpty());
        var root = serverConfig.mediaRoots().getFirst();
        assertEquals("local", root.filesystem());

        assertEquals("ffmpeg", serverConfig.ffmpeg().path());
        assertEquals("ffprobe", serverConfig.ffmpeg().ffprobePath());
        assertEquals("6.0", serverConfig.ffmpeg().minVersion());
        assertEquals(2, serverConfig.ffmpeg().maxConcurrentJobs());

        assertEquals("both", serverConfig.transcode().defaultFormat());
        assertEquals(10, serverConfig.transcode().segmentCacheGracePeriodMinutes());
        assertEquals(6, serverConfig.transcode().segmentDurationSteadyStateSecs());
    }

    @Test
    void toServerConfigUsesCanonicalDatabaseDirectoryEnvironmentOverride() {
        ServerConfigLoader.setEnvironmentLookupForTesting(Map.of(
            "NYX_DATABASE_DIR", "/tmp/nyx-canonical-db"
        )::get);

        ServerConfig serverConfig = load("application.conf");

        assertEquals(Path.of("/tmp/nyx-canonical-db"), serverConfig.database().dir());
    }

    @Test
    void toServerConfigAcceptsLegacyDatabaseDirectoryAliasWhenCanonicalIsUnset() {
        ServerConfigLoader.setEnvironmentLookupForTesting(Map.of(
            "NYX_DB_DIR", "/tmp/nyx-legacy-db"
        )::get);

        ServerConfig serverConfig = load("application.conf");

        assertEquals(Path.of("/tmp/nyx-legacy-db"), serverConfig.database().dir());
    }

    @Test
    void toServerConfigPrefersCanonicalDatabaseDirectoryWhenBothEnvVarsAreSet() {
        ServerConfigLoader.setEnvironmentLookupForTesting(Map.of(
            "NYX_DATABASE_DIR", "/tmp/nyx-canonical-db",
            "NYX_DB_DIR", "/tmp/nyx-legacy-db"
        )::get);

        ServerConfig serverConfig = load("application.conf");

        assertEquals(Path.of("/tmp/nyx-canonical-db"), serverConfig.database().dir());
    }

    @Test
    void mediaRootConfigUsesDefaultValuesForOptionalParameters() {
        MediaRootConfig config = new MediaRootConfig(Path.of("/test-media"));

        assertEquals("local", config.filesystem());
    }

    @Test
    void rateLimitConfigDefaultsAreSafe() {
        RateLimitConfig config = new RateLimitConfig();

        assertFalse(config.enabled());
        assertEquals(100, config.requestsPerSecond());
        assertEquals(1L, config.windowSeconds());
        assertEquals(200, config.burstSize());
    }

    @Test
    void csrfConfigDefaultsAreSafe() {
        CsrfConfig config = new CsrfConfig();

        assertFalse(config.enabled());
    }

    @Test
    void tlsConfigDefaultsAreSafe() {
        TlsConfig config = new TlsConfig();

        assertFalse(config.enabled());
        assertEquals("", config.keystorePath());
        assertEquals("", config.keystorePassword());
        assertEquals("nyx", config.keyAlias());
        assertEquals("", config.keyPassword());
        assertEquals(8443, config.port());
    }

    @Test
    void toServerConfigParsesRateLimitBlock() {
        ServerConfig serverConfig = load("application.conf");

        assertFalse(serverConfig.rateLimit().enabled());
        assertEquals(100, serverConfig.rateLimit().requestsPerSecond());
    }

    @Test
    void toServerConfigParsesCsrfBlock() {
        ServerConfig serverConfig = load("application.conf");

        assertFalse(serverConfig.csrf().enabled());
    }

    @Test
    void toServerConfigParsesTlsBlock() {
        ServerConfig serverConfig = load("application.conf");

        assertFalse(serverConfig.tls().enabled());
        assertEquals(8443, serverConfig.tls().port());
        assertEquals("nyx", serverConfig.tls().keyAlias());
    }

    @Test
    void toServerConfigDefaultsQloudCompatibilityToDisabledInPackagedApplicationConf() {
        ServerConfig serverConfig = load("application.conf");

        assertFalse(serverConfig.compatibility().qloud().enabled());
        assertEquals("0.0.0.0", serverConfig.compatibility().qloud().host());
        assertEquals(8081, serverConfig.compatibility().qloud().port());
    }

    @Test
    void toServerConfigKeepsQloudCompatibilityDisabledWhenSectionIsAbsent() {
        ServerConfig serverConfig = load("application-test-minimal.conf");

        assertFalse(serverConfig.compatibility().qloud().enabled());
        assertEquals("localhost", serverConfig.compatibility().qloud().host());
        assertEquals(9091, serverConfig.compatibility().qloud().port());
    }

    @Test
    void legacyServerConfigConstructorsDefaultCompatibilityListenersToDisabled() {
        ServerConfig minimalConstructor = new ServerConfig(
            "127.0.0.1",
            9000,
            List.of(),
            List.of(new MediaRootConfig(Path.of("/tmp/media"))),
            new FfmpegConfig("ffmpeg", "ffprobe", "6.0", 2),
            new TranscodeConfig("hls", 5, 6),
            new DatabaseConfig(Path.of("/tmp/db"))
        );
        ServerConfig fullLegacyConstructor = new ServerConfig(
            "127.0.0.2",
            9100,
            List.of(),
            List.of(new MediaRootConfig(Path.of("/tmp/media-2"))),
            new FfmpegConfig("ffmpeg", "ffprobe", "6.0", 2),
            new TranscodeConfig("hls", 5, 6),
            new DatabaseConfig(Path.of("/tmp/db-2")),
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

        assertFalse(minimalConstructor.compatibility().qloud().enabled());
        assertEquals("127.0.0.1", minimalConstructor.compatibility().qloud().host());
        assertEquals(9001, minimalConstructor.compatibility().qloud().port());

        assertFalse(fullLegacyConstructor.compatibility().qloud().enabled());
        assertEquals("127.0.0.2", fullLegacyConstructor.compatibility().qloud().host());
        assertEquals(9101, fullLegacyConstructor.compatibility().qloud().port());
    }

    @Test
    void toServerConfigParsesExplicitQloudCompatibilityOverrides() {
        ServerConfig serverConfig = new ApplicationConfig(ConfigFactory.parseString("""
            server {
                host = "127.0.0.1"
                port = 7000
                cors { allowed_origins = [] }
            }
            media { roots = [{ path = "/tmp/media" }] }
            ffmpeg {
                path = "ffmpeg"
                ffprobe_path = "ffprobe"
                min_version = "6.0"
                max_concurrent_jobs = 2
            }
            transcode {
                default_format = "hls"
                segment_cache { grace_period_minutes = 5 }
                segment_duration { steady_state_secs = 6 }
            }
            database { dir = "/tmp/db" }
            compatibility {
                qloud {
                    enabled = false
                    host = "127.0.0.2"
                    port = 7100
                }
            }
            """)).toServerConfig();

        assertFalse(serverConfig.compatibility().qloud().enabled());
        assertEquals("127.0.0.2", serverConfig.compatibility().qloud().host());
        assertEquals(7100, serverConfig.compatibility().qloud().port());
    }

    @Test
    void toServerConfigAppliesEnvironmentOverridesForQloudCompatibility() {
        ServerConfigLoader.setEnvironmentLookupForTesting(Map.of(
            "NYX_QLOUD_COMPAT_ENABLED", "false",
            "NYX_QLOUD_COMPAT_HOST", "192.0.2.25",
            "NYX_QLOUD_COMPAT_PORT", "7200"
        )::get);

        ServerConfig serverConfig = new ApplicationConfig(ConfigFactory.parseString("""
            server {
                host = "127.0.0.1"
                port = 7000
                cors { allowed_origins = [] }
            }
            media { roots = [{ path = "/tmp/media" }] }
            ffmpeg {
                path = "ffmpeg"
                ffprobe_path = "ffprobe"
                min_version = "6.0"
                max_concurrent_jobs = 2
            }
            transcode {
                default_format = "hls"
                segment_cache { grace_period_minutes = 5 }
                segment_duration { steady_state_secs = 6 }
            }
            database { dir = "/tmp/db" }
            compatibility {
                qloud {
                    enabled = true
                    host = "127.0.0.2"
                    port = 7100
                }
            }
            """)).toServerConfig();

        assertFalse(serverConfig.compatibility().qloud().enabled());
        assertEquals("192.0.2.25", serverConfig.compatibility().qloud().host());
        assertEquals(7200, serverConfig.compatibility().qloud().port());
    }

    @Test
    void toServerConfigAppliesEnvironmentOverridesForQloudCompatibilityWhenSectionIsAbsent() {
        ServerConfigLoader.setEnvironmentLookupForTesting(Map.of(
            "NYX_QLOUD_COMPAT_ENABLED", "false",
            "NYX_QLOUD_COMPAT_HOST", "192.0.2.26",
            "NYX_QLOUD_COMPAT_PORT", "7300"
        )::get);

        ServerConfig serverConfig = load("application-test-minimal.conf");

        assertFalse(serverConfig.compatibility().qloud().enabled());
        assertEquals("192.0.2.26", serverConfig.compatibility().qloud().host());
        assertEquals(7300, serverConfig.compatibility().qloud().port());
    }

    @Test
    void toServerConfigAppliesEnvironmentOverridesForQloudCompatibilityWhenQloudSubsectionIsAbsent() {
        ServerConfigLoader.setEnvironmentLookupForTesting(Map.of(
            "NYX_QLOUD_COMPAT_ENABLED", "false",
            "NYX_QLOUD_COMPAT_HOST", "192.0.2.27",
            "NYX_QLOUD_COMPAT_PORT", "7400"
        )::get);

        ServerConfig serverConfig = new ApplicationConfig(ConfigFactory.parseString("""
            server {
                host = "127.0.0.1"
                port = 7000
                cors { allowed_origins = [] }
            }
            media { roots = [{ path = "/tmp/media" }] }
            ffmpeg {
                path = "ffmpeg"
                ffprobe_path = "ffprobe"
                min_version = "6.0"
                max_concurrent_jobs = 2
            }
            transcode {
                default_format = "hls"
                segment_cache { grace_period_minutes = 5 }
                segment_duration { steady_state_secs = 6 }
            }
            database { dir = "/tmp/db" }
            compatibility {}
            """)).toServerConfig();

        assertFalse(serverConfig.compatibility().qloud().enabled());
        assertEquals("192.0.2.27", serverConfig.compatibility().qloud().host());
        assertEquals(7400, serverConfig.compatibility().qloud().port());
    }

    @Test
    void ffmpegConfigHasMaxQueuedJobsDefaultingTo8() {
        FfmpegConfig config = new FfmpegConfig("ffmpeg", "ffprobe", "6.0", 2);

        assertEquals(8, config.maxQueuedJobs());
    }

    @Test
    void ffmpegConfigMaxQueuedJobsCanBeOverridden() {
        FfmpegConfig config = new FfmpegConfig(
            "ffmpeg",
            "ffprobe",
            "6.0",
            2,
            4,
            32,
            FfmpegConfig.DEFAULT_QUALITY_PRESETS,
            "polling",
            500L
        );

        assertEquals(32, config.maxQueuedJobs());
    }

    @Test
    void toServerConfigReadsMaxQueuedJobsFromHocon() {
        ServerConfig serverConfig = load("application.conf");

        assertEquals(8, serverConfig.ffmpeg().maxQueuedJobs());
    }

    @Test
    void quotaConfigDefaultsAreSafe() {
        QuotaConfig config = new QuotaConfig();

        assertFalse(config.enabled());
        assertEquals(4, config.defaultMaxConcurrentJobs());
        assertEquals(60, config.defaultMaxRequestsPerMinute());
        assertEquals(10_737_418_240L, config.defaultMaxStorageBytes());
        assertTrue(config.userOverrides().isEmpty());
    }

    @Test
    void authConfigTokensDefaultsToEmptyMap() {
        AuthConfig config = new AuthConfig();

        assertTrue(config.tokens().isEmpty());
    }

    @Test
    void toServerConfigParsesQuotaBlock() {
        ServerConfig serverConfig = load("application.conf");

        assertFalse(serverConfig.quota().enabled());
        assertEquals(4, serverConfig.quota().defaultMaxConcurrentJobs());
        assertEquals(60, serverConfig.quota().defaultMaxRequestsPerMinute());
        assertEquals(10_737_418_240L, serverConfig.quota().defaultMaxStorageBytes());
        assertTrue(serverConfig.quota().userOverrides().isEmpty());
    }

    @Test
    void toServerConfigParsesAuthTokensBlock() {
        ServerConfig serverConfig = load("application.conf");

        assertTrue(serverConfig.auth().tokens().isEmpty());
    }

    @Test
    void toServerConfigUsesAuthAndThumbnailDefaultsWhenSectionsAreAbsent() {
        ServerConfig serverConfig = load("application-test-minimal.conf");

        assertFalse(serverConfig.auth().enabled());
        assertEquals("", serverConfig.auth().token());
        assertEquals(List.of(150, 300, 600), serverConfig.thumbnails().sizes());
        assertEquals(524_288_000L, serverConfig.transcode().minFreeDiskBytes());
    }

    @Test
    void toServerConfigFailsClearlyForInvalidOptionalSectionValues() {
        ApplicationConfig config = new ApplicationConfig("application-test-invalid-rate-limit.conf");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, config::toServerConfig);
        assertTrue(error.getMessage().contains("rate_limit"));
    }

    @Test
    void toServerConfigParsesWebhooksBlock() {
        ServerConfig serverConfig = load("application.conf");

        assertFalse(serverConfig.webhooks().enabled());
        assertEquals(3, serverConfig.webhooks().maxRetries());
        assertEquals(5_000L, serverConfig.webhooks().retryBackoffMs());
        assertEquals(30_000L, serverConfig.webhooks().timeoutMs());
        assertEquals(10, serverConfig.webhooks().maxConcurrentDeliveries());
        assertTrue(serverConfig.webhooks().allowedHosts().isEmpty());
        assertEquals(7, serverConfig.webhooks().deliveryRetentionDays());
        assertEquals(60, serverConfig.webhooks().cleanupIntervalMinutes());
    }

    @Test
    void toServerConfigParsesBackupBlock() {
        ServerConfig serverConfig = load("application.conf");

        assertFalse(serverConfig.backup().enabled());
        assertEquals("", serverConfig.backup().dir());
        assertEquals(0, serverConfig.backup().scheduleIntervalMinutes());
        assertEquals(5, serverConfig.backup().retainCount());
    }

    @Test
    void toServerConfigParsesStorageBlock() {
        ServerConfig serverConfig = load("application.conf");

        assertEquals("local", serverConfig.storage().backend());
        assertEquals(Path.of("data/cache"), serverConfig.storage().localCacheDir());
        assertEquals("", serverConfig.storage().s3().bucket());
        assertEquals("us-east-1", serverConfig.storage().s3().region());
        assertEquals("", serverConfig.storage().s3().endpoint());
        assertTrue(serverConfig.storage().s3().pathStyleAccess());
    }

    @Test
    void toServerConfigParsesThumbnailsBlock() {
        ServerConfig serverConfig = load("application.conf");

        assertEquals(List.of(150, 300, 600), serverConfig.thumbnails().sizes());
        assertEquals(10, serverConfig.thumbnails().videoOffsetPercent());
        assertEquals(1024, serverConfig.thumbnails().maxCacheSizeMB());
        assertEquals(60, serverConfig.thumbnails().cleanupIntervalMinutes());
    }

    @Test
    void minimalConfigFallsBackToDefaultsForWebhooksBackupStorage() {
        ServerConfig serverConfig = load("application-test-minimal.conf");

        assertFalse(serverConfig.webhooks().enabled());
        assertFalse(serverConfig.backup().enabled());
        assertEquals("local", serverConfig.storage().backend());
    }

    @Test
    void toServerConfigWithMissingOptionalSectionsReturnsDefaults() {
        ServerConfig serverConfig = load("application-test-missing-sections.conf");

        assertEquals("0.0.0.0", serverConfig.host());
        assertEquals(8080, serverConfig.port());
        assertTrue(serverConfig.corsOrigins().isEmpty());
        assertEquals(1, serverConfig.mediaRoots().size());
        assertEquals(Path.of("/tmp/nyx-test-media"), serverConfig.mediaRoots().getFirst().path());

        assertEquals("/usr/bin/ffmpeg", serverConfig.ffmpeg().path());
        assertEquals("/usr/bin/ffprobe", serverConfig.ffmpeg().ffprobePath());
        assertEquals("4.0", serverConfig.ffmpeg().minVersion());
        assertEquals(2, serverConfig.ffmpeg().maxConcurrentJobs());

        assertEquals("hls", serverConfig.transcode().defaultFormat());
        assertEquals(5, serverConfig.transcode().segmentCacheGracePeriodMinutes());
        assertEquals(1000, serverConfig.transcode().segmentCacheMaxEntries());
        assertEquals(6, serverConfig.transcode().segmentDurationSteadyStateSecs());

        assertEquals(Path.of("/tmp/nyx-test-db"), serverConfig.database().dir());

        assertFalse(serverConfig.auth().enabled());
        assertEquals("", serverConfig.auth().token());
        assertTrue(serverConfig.auth().users().isEmpty());
        assertTrue(serverConfig.auth().tokens().isEmpty());

        assertEquals(List.of(150, 300, 600), serverConfig.thumbnails().sizes());
        assertEquals(10, serverConfig.thumbnails().videoOffsetPercent());
        assertEquals(1024, serverConfig.thumbnails().maxCacheSizeMB());
        assertEquals(60, serverConfig.thumbnails().cleanupIntervalMinutes());

        assertFalse(serverConfig.rateLimit().enabled());
        assertEquals(100, serverConfig.rateLimit().requestsPerSecond());
        assertEquals(1L, serverConfig.rateLimit().windowSeconds());
        assertEquals(200, serverConfig.rateLimit().burstSize());

        assertFalse(serverConfig.csrf().enabled());

        assertFalse(serverConfig.tls().enabled());
        assertEquals("", serverConfig.tls().keystorePath());
        assertEquals("", serverConfig.tls().keystorePassword());
        assertEquals("nyx", serverConfig.tls().keyAlias());
        assertEquals("", serverConfig.tls().keyPassword());
        assertEquals(8443, serverConfig.tls().port());

        assertFalse(serverConfig.quota().enabled());
        assertEquals(4, serverConfig.quota().defaultMaxConcurrentJobs());
        assertEquals(60, serverConfig.quota().defaultMaxRequestsPerMinute());
        assertEquals(10_737_418_240L, serverConfig.quota().defaultMaxStorageBytes());
        assertTrue(serverConfig.quota().userOverrides().isEmpty());

        assertFalse(serverConfig.webhooks().enabled());
        assertEquals(3, serverConfig.webhooks().maxRetries());
        assertEquals(5_000L, serverConfig.webhooks().retryBackoffMs());
        assertEquals(30_000L, serverConfig.webhooks().timeoutMs());
        assertEquals(10, serverConfig.webhooks().maxConcurrentDeliveries());
        assertTrue(serverConfig.webhooks().allowedHosts().isEmpty());
        assertEquals(7, serverConfig.webhooks().deliveryRetentionDays());
        assertEquals(60, serverConfig.webhooks().cleanupIntervalMinutes());

        assertFalse(serverConfig.backup().enabled());
        assertEquals("", serverConfig.backup().dir());
        assertEquals(0, serverConfig.backup().scheduleIntervalMinutes());
        assertEquals(5, serverConfig.backup().retainCount());

        assertEquals("local", serverConfig.storage().backend());
        assertEquals(Path.of("data/cache"), serverConfig.storage().localCacheDir());
        assertEquals("", serverConfig.storage().s3().bucket());
        assertEquals("", serverConfig.storage().s3().endpoint());
        assertEquals("us-east-1", serverConfig.storage().s3().region());
        assertEquals("", serverConfig.storage().s3().accessKey());
        assertEquals("", serverConfig.storage().s3().secretKey());
        assertEquals("", serverConfig.storage().s3().prefix());
        assertTrue(serverConfig.storage().s3().pathStyleAccess());
        assertFalse(serverConfig.compatibility().qloud().enabled());
        assertEquals("0.0.0.0", serverConfig.compatibility().qloud().host());
        assertEquals(8081, serverConfig.compatibility().qloud().port());
    }

    @Test
    void toServerConfigWithMissingQualityPresetsReturnsDefaults() {
        ServerConfig serverConfig = load("application-test-missing-sections.conf");

        assertEquals(FfmpegConfig.DEFAULT_QUALITY_PRESETS, serverConfig.ffmpeg().qualityPresets());
        assertEquals("h264_fast", serverConfig.ffmpeg().qualityPresets().get("low"));
        assertEquals("h264_balanced", serverConfig.ffmpeg().qualityPresets().get("medium"));
        assertEquals("h265_quality", serverConfig.ffmpeg().qualityPresets().get("high"));
    }

    @Test
    void toServerConfigWithMissingOptionalTranscodeFieldsUsesDefaults() {
        ServerConfig serverConfig = load("application-test-missing-sections.conf");

        assertEquals(524_288_000L, serverConfig.transcode().minFreeDiskBytes());
        assertEquals(3, serverConfig.transcode().maxRetries());
        assertEquals(2_000L, serverConfig.transcode().retryBackoffMs());
        assertEquals(5, serverConfig.transcode().circuitBreakerThreshold());
    }

    @Test
    void toServerConfigWithMissingOptionalDatabaseFieldsUsesDefaults() {
        ServerConfig serverConfig = load("application-test-missing-sections.conf");

        assertEquals(4, serverConfig.database().poolSize());
        assertEquals(600_000L, serverConfig.database().idleTimeoutMs());
        assertEquals(1_800_000L, serverConfig.database().maxLifetimeMs());
    }

    @Test
    void toServerConfigWithMissingOptionalFfmpegFieldsUsesDefaults() {
        ServerConfig serverConfig = load("application-test-missing-sections.conf");

        assertEquals(4, serverConfig.ffmpeg().maxConcurrentMediaProcesses());
        assertEquals(8, serverConfig.ffmpeg().maxQueuedJobs());
    }

    @Test
    void toServerConfigWithMissingOptionalMediaRootFieldsUsesDefaults() {
        ServerConfig serverConfig = load("application-test-missing-sections.conf");

        assertEquals("local", serverConfig.mediaRoots().getFirst().filesystem());
    }

    @Test
    void toServerConfigParsesAuthUsersAndTokensFromHocon() {
        ServerConfig serverConfig = load("application-test-full.conf");

        assertTrue(serverConfig.auth().enabled());
        assertEquals("test-token", serverConfig.auth().token());
        assertEquals(2, serverConfig.auth().users().size());
        assertEquals("fakehash1", serverConfig.auth().users().get("admin"));
        assertEquals("fakehash2", serverConfig.auth().users().get("editor"));
        assertEquals(2, serverConfig.auth().tokens().size());
        assertEquals("user1", serverConfig.auth().tokens().get("apikey123"));
        assertEquals("user2", serverConfig.auth().tokens().get("apikey456"));
    }

    @Test
    void toServerConfigParsesQuotaConfigFields() {
        ServerConfig serverConfig = load("application-test-full.conf");

        assertTrue(serverConfig.quota().enabled());
        assertEquals(8, serverConfig.quota().defaultMaxConcurrentJobs());
        assertEquals(120, serverConfig.quota().defaultMaxRequestsPerMinute());
        assertEquals(21_474_836_480L, serverConfig.quota().defaultMaxStorageBytes());
        assertNotNull(serverConfig.quota().userOverrides());
    }

    @Test
    void toServerConfigParsesFfmpegQualityPresetsFromHocon() {
        ServerConfig serverConfig = load("application-test-full.conf");

        assertEquals("h264_fast", serverConfig.ffmpeg().qualityPresets().get("low"));
        assertEquals("h264_balanced", serverConfig.ffmpeg().qualityPresets().get("medium"));
        assertEquals("h265_quality", serverConfig.ffmpeg().qualityPresets().get("high"));
    }

    @Test
    void authSectionWithoutUsersSubSectionTriggersCatchBlockReturningEmptyMap() {
        ServerConfig serverConfig = load("application-test-auth-no-users.conf");

        assertTrue(serverConfig.auth().enabled());
        assertEquals("test-token-no-users", serverConfig.auth().token());
        assertTrue(serverConfig.auth().users().isEmpty());
    }

    @Test
    void authSectionWithoutTokensSubSectionTriggersCatchBlockReturningEmptyMap() {
        ServerConfig serverConfig = load("application-test-auth-no-users.conf");

        assertTrue(serverConfig.auth().tokens().isEmpty());
    }

    @Test
    void webhooksWithAllowedHostsParsesHostListFromHocon() {
        ServerConfig serverConfig = load("application-test-webhook-hosts.conf");

        assertTrue(serverConfig.webhooks().enabled());
        var hosts = serverConfig.webhooks().allowedHosts();
        assertEquals(3, hosts.size());
        assertTrue(hosts.contains("host1.example.com"));
        assertTrue(hosts.contains("host2.example.com"));
        assertTrue(hosts.contains("api.example.org"));
    }

    @Test
    void storageSectionWithoutS3SubSectionTriggersCatchBlockReturningS3ConfigDefaults() {
        ServerConfig serverConfig = load("application-test-storage-no-s3.conf");

        assertEquals("local", serverConfig.storage().backend());
        var s3 = serverConfig.storage().s3();
        assertEquals("", s3.bucket());
        assertEquals("", s3.endpoint());
        assertEquals("us-east-1", s3.region());
        assertEquals("", s3.accessKey());
        assertEquals("", s3.secretKey());
        assertEquals("", s3.prefix());
        assertTrue(s3.pathStyleAccess());
    }

    private static ServerConfig load(String resourceName) {
        return new ApplicationConfig(resourceName).toServerConfig();
    }
}
