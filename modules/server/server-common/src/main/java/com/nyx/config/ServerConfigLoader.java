package com.nyx.config;

import com.typesafe.config.ConfigException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

final class ServerConfigLoader {
    private static final String DATABASE_DIR_ENV = "NYX_DATABASE_DIR";
    private static final String LEGACY_DATABASE_DIR_ENV = "NYX_DB_DIR";
    private static volatile Function<String, String> environmentLookup = System::getenv;

    private ServerConfigLoader() {
    }

    static ServerConfig toServerConfig(ApplicationConfig config) {
        ApplicationConfig serverConfig = config.config("server");
        String serverHost = serverConfig.property("host").getString();
        int serverPort = Integer.parseInt(serverConfig.property("port").getString());
        ApplicationConfig mediaConfig = config.config("media");
        List<MediaRootConfig> mediaRoots = mediaConfig.configList("roots").stream()
            .map(rootConfig -> new MediaRootConfig(
                Path.of(rootConfig.property("path").getString()),
                stringOr(rootConfig, "filesystem", "local"),
                stringOr(rootConfig, "display_name", "")
            ))
            .toList();

        FfmpegConfig ffmpegConfig = parseRequiredSection(config, "ffmpeg", ff -> new FfmpegConfig(
            ff.property("path").getString(),
            ff.property("ffprobe_path").getString(),
            ff.property("min_version").getString(),
            Integer.parseInt(ff.property("max_concurrent_jobs").getString()),
            intOr(ff, "max_concurrent_media_processes", 4),
            intOr(ff, "max_queued_jobs", 8),
            parseKeyValueSection(ff, "quality_presets").isEmpty()
                ? FfmpegConfig.DEFAULT_QUALITY_PRESETS
                : parseKeyValueSection(ff, "quality_presets"),
            stringOr(ff, "watch_strategy", "polling"),
            longOr(ff, "segment_watch_poll_interval_ms", 500L)
        ));

        TranscodeConfig transcodeConfig = parseRequiredSection(config, "transcode", tc -> new TranscodeConfig(
            tc.property("default_format").getString(),
            Integer.parseInt(tc.config("segment_cache").property("grace_period_minutes").getString()),
            Integer.parseInt(tc.config("segment_duration").property("steady_state_secs").getString()),
            intOr(tc.config("segment_cache"), "max_entries", 10_000),
            longOr(tc, "min_free_disk_bytes", 524_288_000L),
            envIntOr("NYX_MAX_RETRIES", intOr(tc, "max_retries", 3)),
            envLongOr("NYX_RETRY_BACKOFF_MS", longOr(tc, "retry_backoff_ms", 2_000L)),
            envIntOr("NYX_CIRCUIT_BREAKER_THRESHOLD", intOr(tc, "circuit_breaker_threshold", 5))
        ));

        DatabaseConfig databaseConfig = parseRequiredSection(config, "database", db -> new DatabaseConfig(
            Path.of(databaseDir(db)),
            envIntOr("NYX_DB_POOL_SIZE", intOr(db, "pool_size", 4)),
            envLongOr("NYX_DB_IDLE_TIMEOUT_MS", longOr(db, "idle_timeout_ms", 600_000L)),
            envLongOr("NYX_DB_MAX_LIFETIME_MS", longOr(db, "max_lifetime_ms", 1_800_000L))
        ));

        AuthConfig authConfig = parseOptionalSection(config, "auth", new AuthConfig(), auth -> new AuthConfig(
            boolOr(auth, "enabled", false),
            stringOr(auth, "token", ""),
            mergeMaps(parseKeyValueSection(auth, "users"), parseEnvKeyValues("NYX_AUTH_USERS")),
            mergeMaps(parseKeyValueSection(auth, "tokens"), parseEnvKeyValues("NYX_AUTH_TOKENS"))
        ));

        ThumbnailConfig thumbnailConfig = parseOptionalSection(
            config,
            "thumbnails",
            new ThumbnailConfig(),
            thumbnails -> new ThumbnailConfig(
                thumbnails.propertyOrNull("sizes") != null
                    ? thumbnails.propertyOrNull("sizes").getList().stream().map(Integer::parseInt).toList()
                    : List.of(150, 300, 600),
                intOr(thumbnails, "video_offset_percent", 10),
                longOr(thumbnails, "max_cache_size_mb", 1024L),
                intOr(thumbnails, "cleanup_interval_minutes", 60)
            )
        );

        RateLimitConfig rateLimitConfig = parseOptionalSection(
            config,
            "rate_limit",
            new RateLimitConfig(),
            rateLimit -> new RateLimitConfig(
                boolOr(rateLimit, "enabled", false),
                intOr(rateLimit, "requests_per_second", 100),
                longOr(rateLimit, "window_seconds", 1L),
                intOr(rateLimit, "burst_size", 200)
            )
        );

        CsrfConfig csrfConfig = parseOptionalSection(
            config,
            "csrf",
            new CsrfConfig(),
            csrf -> new CsrfConfig(boolOr(csrf, "enabled", false))
        );

        TlsConfig tlsConfig = parseOptionalSection(
            config,
            "tls",
            new TlsConfig(),
            tls -> new TlsConfig(
                boolOr(tls, "enabled", false),
                stringOr(tls, "keystore_path", ""),
                stringOr(tls, "keystore_password", ""),
                stringOr(tls, "key_alias", "nyx"),
                stringOr(tls, "key_password", ""),
                intOr(tls, "port", 8443)
            )
        );

        CompatibilityConfig compatibilityConfig = applyCompatibilityEnvironmentOverrides(
            parseOptionalSection(
                config,
                "compatibility",
                new CompatibilityConfig(),
                compatibility -> new CompatibilityConfig(
                    parseOptionalSection(
                        compatibility,
                        "qloud",
                        new QloudCompatibilityConfig(),
                        qloud -> new QloudCompatibilityConfig(
                            boolOr(qloud, "enabled", false),
                            stringOr(qloud, "host", null),
                            intOr(qloud, "port", QloudCompatibilityConfig.DEFAULT_PORT_SENTINEL)
                        )
                    )
                )
            ).resolveDefaults(serverHost, serverPort)
        );

        QuotaConfig quotaConfig = parseOptionalSection(
            config,
            "quota",
            new QuotaConfig(),
            quota -> new QuotaConfig(
                boolOr(quota, "enabled", false),
                intOr(quota, "default_max_concurrent_jobs", 4),
                intOr(quota, "default_max_requests_per_minute", 60),
                longOr(quota, "default_max_storage_bytes", 10_737_418_240L),
                parseUserOverrides(quota)
            )
        );

        WebhookConfig webhookConfig = parseOptionalSection(
            config,
            "webhooks",
            new WebhookConfig(),
            webhook -> {
                Set<String> hoconHosts = webhook.propertyOrNull("allowed_hosts") != null
                    ? new LinkedHashSet<>(webhook.propertyOrNull("allowed_hosts").getList())
                    : Set.of();
                Set<String> envHosts = parseCsvEnv("NYX_WEBHOOK_ALLOWED_HOSTS");
                LinkedHashSet<String> allowedHosts = new LinkedHashSet<>(hoconHosts);
                allowedHosts.addAll(envHosts);
                return new WebhookConfig(
                    boolOr(webhook, "enabled", false),
                    intOr(webhook, "max_retries", 3),
                    longOr(webhook, "retry_backoff_ms", 5_000L),
                    longOr(webhook, "timeout_ms", 30_000L),
                    intOr(webhook, "max_concurrent_deliveries", 10),
                    allowedHosts,
                    envIntOr("NYX_WEBHOOK_RETENTION_DAYS", intOr(webhook, "delivery_retention_days", 7)),
                    envIntOr("NYX_WEBHOOK_CLEANUP_INTERVAL", intOr(webhook, "cleanup_interval_minutes", 60))
                );
            }
        );

        BackupConfig backupConfig = parseOptionalSection(
            config,
            "backup",
            new BackupConfig(),
            backup -> new BackupConfig(
                envBooleanOr("NYX_BACKUP_ENABLED", boolOr(backup, "enabled", false)),
                envStringOr("NYX_BACKUP_DIR", stringOr(backup, "dir", "")),
                envIntOr("NYX_BACKUP_INTERVAL_MINUTES", intOr(backup, "schedule_interval_minutes", 0)),
                envIntOr("NYX_BACKUP_RETAIN_COUNT", intOr(backup, "retain_count", 5))
            )
        );

        AudioConfig audioConfig = parseOptionalSection(
            config,
            "audio",
            new AudioConfig(),
            audio -> new AudioConfig(
                stringOr(audio, "aac_bitrate", "256k"),
                stringOr(audio, "opus_bitrate", "128k"),
                stringOr(audio, "mp3_bitrate", "192k"),
                longOr(audio, "process_timeout_seconds", 30L)
            )
        );

        StorageConfig storageConfig = parseOptionalSection(
            config,
            "storage",
            new StorageConfig(),
            storage -> {
                ApplicationConfig s3 = configOrNull(storage, "s3");
                S3Config s3Config = s3 != null
                    ? new S3Config(
                        envStringOr("NYX_S3_BUCKET", stringOr(s3, "bucket", "")),
                        envStringOr("NYX_S3_ENDPOINT", stringOr(s3, "endpoint", "")),
                        envStringOr("NYX_S3_REGION", stringOr(s3, "region", "us-east-1")),
                        envStringOr("NYX_S3_ACCESS_KEY", stringOr(s3, "access_key", "")),
                        envStringOr("NYX_S3_SECRET_KEY", stringOr(s3, "secret_key", "")),
                        envStringOr("NYX_S3_PREFIX", stringOr(s3, "prefix", "")),
                        boolOr(s3, "path_style_access", true)
                    )
                    : new S3Config();
                return new StorageConfig(
                    envStringOr("NYX_STORAGE_BACKEND", stringOr(storage, "backend", "local")),
                    Path.of(envStringOr("NYX_STORAGE_LOCAL_CACHE_DIR", stringOr(storage, "local_cache_dir", "data/cache"))),
                    s3Config
                );
            }
        );

        return new ServerConfig(
            serverHost,
            serverPort,
            serverConfig.config("cors").property("allowed_origins").getList(),
            mediaRoots,
            ffmpegConfig,
            transcodeConfig,
            databaseConfig,
            thumbnailConfig,
            audioConfig,
            authConfig,
            rateLimitConfig,
            csrfConfig,
            tlsConfig,
            webhookConfig,
            quotaConfig,
            backupConfig,
            storageConfig,
            compatibilityConfig
        );
    }

    static void setEnvironmentLookupForTesting(Function<String, String> environmentLookup) {
        ServerConfigLoader.environmentLookup = Objects.requireNonNull(environmentLookup, "environmentLookup");
    }

    static void resetEnvironmentLookupForTesting() {
        ServerConfigLoader.environmentLookup = System::getenv;
    }

    private static Map<String, UserQuotaOverride> parseUserOverrides(ApplicationConfig quota) {
        Map<String, UserQuotaOverride> overrides = new LinkedHashMap<>();
        for (String userId : childSectionNames(quota, "user_overrides")) {
            String prefix = "user_overrides." + userId;
            overrides.put(
                userId,
                new UserQuotaOverride(
                    parseOptionalInt(quota.propertyOrNull(prefix + ".max_concurrent_jobs")),
                    parseOptionalInt(quota.propertyOrNull(prefix + ".max_requests_per_minute")),
                    parseOptionalLong(quota.propertyOrNull(prefix + ".max_storage_bytes"))
                )
            );
        }
        return overrides;
    }

    private static Map<String, String> mergeMaps(Map<String, String> left, Map<String, String> right) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(left);
        merged.putAll(right);
        return merged;
    }

    private static CompatibilityConfig applyCompatibilityEnvironmentOverrides(CompatibilityConfig compatibilityConfig) {
        return new CompatibilityConfig(applyQloudCompatibilityEnvironmentOverrides(compatibilityConfig.qloud()));
    }

    private static QloudCompatibilityConfig applyQloudCompatibilityEnvironmentOverrides(
        QloudCompatibilityConfig qloudConfig
    ) {
        return new QloudCompatibilityConfig(
            envBooleanOr("NYX_QLOUD_COMPAT_ENABLED", qloudConfig.enabled()),
            envStringOr("NYX_QLOUD_COMPAT_HOST", qloudConfig.host()),
            envIntOr("NYX_QLOUD_COMPAT_PORT", qloudConfig.port())
        );
    }

    private static String databaseDir(ApplicationConfig db) {
        String canonical = env(DATABASE_DIR_ENV);
        if (canonical != null && !canonical.isBlank()) {
            return canonical;
        }
        String legacy = env(LEGACY_DATABASE_DIR_ENV);
        if (legacy != null && !legacy.isBlank()) {
            return legacy;
        }
        return db.property("dir").getString();
    }

    private static Integer parseOptionalInt(ApplicationProperty property) {
        if (property == null) {
            return null;
        }
        return Integer.valueOf(property.getString());
    }

    private static Long parseOptionalLong(ApplicationProperty property) {
        if (property == null) {
            return null;
        }
        return Long.valueOf(property.getString());
    }

    private static String stringOr(ApplicationConfig config, String key, String defaultValue) {
        ApplicationProperty property = config.propertyOrNull(key);
        return property != null ? property.getString() : defaultValue;
    }

    private static int intOr(ApplicationConfig config, String key, int defaultValue) {
        ApplicationProperty property = config.propertyOrNull(key);
        return property != null ? Integer.parseInt(property.getString()) : defaultValue;
    }

    private static long longOr(ApplicationConfig config, String key, long defaultValue) {
        ApplicationProperty property = config.propertyOrNull(key);
        return property != null ? Long.parseLong(property.getString()) : defaultValue;
    }

    private static boolean boolOr(ApplicationConfig config, String key, boolean defaultValue) {
        ApplicationProperty property = config.propertyOrNull(key);
        return property != null ? Boolean.parseBoolean(property.getString()) : defaultValue;
    }

    private static ApplicationConfig configOrNull(ApplicationConfig config, String section) {
        try {
            return config.config(section);
        } catch (ConfigException.Missing missing) {
            return null;
        }
    }

    private static <T> T parseOptionalSection(
        ApplicationConfig config,
        String section,
        T defaultValue,
        ConfigParser<T> parser
    ) {
        ApplicationConfig sectionConfig = configOrNull(config, section);
        if (sectionConfig == null) {
            return defaultValue;
        }
        try {
            return parser.parse(sectionConfig);
        } catch (Exception exception) {
            throw invalidSection(section, exception);
        }
    }

    private static <T> T parseRequiredSection(
        ApplicationConfig config,
        String section,
        ConfigParser<T> parser
    ) {
        ApplicationConfig sectionConfig;
        try {
            sectionConfig = config.config(section);
        } catch (Exception exception) {
            throw invalidSection(section, exception);
        }
        try {
            return parser.parse(sectionConfig);
        } catch (Exception exception) {
            throw invalidSection(section, exception);
        }
    }

    private static IllegalArgumentException invalidSection(String section, Exception cause) {
        return new IllegalArgumentException("Invalid '" + section + "' config section: " + cause.getMessage(), cause);
    }

    private static Map<String, String> parseKeyValueSection(ApplicationConfig config, String section) {
        String prefix = section + ".";
        List<String> matchingKeys = config.keys().stream().filter(key -> key.startsWith(prefix)).toList();
        if (matchingKeys.isEmpty()) {
            try {
                config.config(section);
                return Map.of();
            } catch (ConfigException.Missing missing) {
                return Map.of();
            } catch (Exception exception) {
                throw invalidSection(section, exception);
            }
        }

        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        try {
            for (String key : matchingKeys) {
                values.put(key.substring(prefix.length()), config.property(key).getString());
            }
            return values;
        } catch (Exception exception) {
            throw invalidSection(section, exception);
        }
    }

    private static Set<String> childSectionNames(ApplicationConfig config, String section) {
        String prefix = section + ".";
        List<String> matchingKeys = config.keys().stream().filter(key -> key.startsWith(prefix)).toList();
        if (matchingKeys.isEmpty()) {
            try {
                config.config(section);
                return Set.of();
            } catch (ConfigException.Missing missing) {
                return Set.of();
            } catch (Exception exception) {
                throw invalidSection(section, exception);
            }
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String key : matchingKeys) {
            String remainder = key.substring(prefix.length());
            int separator = remainder.indexOf('.');
            names.add(separator >= 0 ? remainder.substring(0, separator) : remainder);
        }
        return names;
    }

    private static Map<String, String> parseEnvKeyValues(String envName) {
        String raw = env(envName);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }

        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2) {
                values.put(parts[0].trim(), parts[1].trim());
            }
        }
        return values;
    }

    private static Set<String> parseCsvEnv(String envName) {
        String raw = env(envName);
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : raw.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static int envIntOr(String envName, int defaultValue) {
        String raw = env(envName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw);
    }

    private static long envLongOr(String envName, long defaultValue) {
        String raw = env(envName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(raw);
    }

    private static boolean envBooleanOr(String envName, boolean defaultValue) {
        String raw = env(envName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw);
    }

    private static String envStringOr(String envName, String defaultValue) {
        String raw = env(envName);
        return raw == null ? defaultValue : raw;
    }

    private static String env(String envName) {
        return environmentLookup.apply(envName);
    }

    @FunctionalInterface
    private interface ConfigParser<T> {
        T parse(ApplicationConfig config);
    }
}
