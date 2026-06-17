package com.nyx.config;

import java.util.List;
import java.util.Objects;

public record ServerConfig(
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
    public ServerConfig {
        host = Objects.requireNonNull(host, "host");
        corsOrigins = List.copyOf(Objects.requireNonNull(corsOrigins, "corsOrigins"));
        mediaRoots = List.copyOf(Objects.requireNonNull(mediaRoots, "mediaRoots"));
        ffmpeg = Objects.requireNonNull(ffmpeg, "ffmpeg");
        transcode = Objects.requireNonNull(transcode, "transcode");
        database = Objects.requireNonNull(database, "database");
        thumbnails = thumbnails == null ? new ThumbnailConfig() : thumbnails;
        audio = audio == null ? new AudioConfig() : audio;
        auth = auth == null ? new AuthConfig() : auth;
        rateLimit = rateLimit == null ? new RateLimitConfig() : rateLimit;
        csrf = csrf == null ? new CsrfConfig() : csrf;
        tls = tls == null ? new TlsConfig() : tls;
        webhooks = webhooks == null ? new WebhookConfig() : webhooks;
        quota = quota == null ? new QuotaConfig() : quota;
        backup = backup == null ? new BackupConfig() : backup;
        storage = storage == null ? new StorageConfig() : storage;
        compatibility = compatibility == null
            ? new CompatibilityConfig().resolveDefaults(host, port)
            : compatibility.resolveDefaults(host, port);
    }

    public ServerConfig(
        String host,
        int port,
        List<String> corsOrigins,
        List<MediaRootConfig> mediaRoots,
        FfmpegConfig ffmpeg,
        TranscodeConfig transcode,
        DatabaseConfig database
    ) {
        this(
            host,
            port,
            corsOrigins,
            mediaRoots,
            ffmpeg,
            transcode,
            database,
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
            null
        );
    }

    public ServerConfig(
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
        this(
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
            null
        );
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public List<String> getCorsOrigins() {
        return corsOrigins;
    }

    public List<MediaRootConfig> getMediaRoots() {
        return mediaRoots;
    }

    public FfmpegConfig getFfmpeg() {
        return ffmpeg;
    }

    public TranscodeConfig getTranscode() {
        return transcode;
    }

    public DatabaseConfig getDatabase() {
        return database;
    }

    public ThumbnailConfig getThumbnails() {
        return thumbnails;
    }

    public AudioConfig getAudio() {
        return audio;
    }

    public AuthConfig getAuth() {
        return auth;
    }

    public RateLimitConfig getRateLimit() {
        return rateLimit;
    }

    public CsrfConfig getCsrf() {
        return csrf;
    }

    public TlsConfig getTls() {
        return tls;
    }

    public WebhookConfig getWebhooks() {
        return webhooks;
    }

    public QuotaConfig getQuota() {
        return quota;
    }

    public BackupConfig getBackup() {
        return backup;
    }

    public StorageConfig getStorage() {
        return storage;
    }

    public CompatibilityConfig getCompatibility() {
        return compatibility;
    }
}
