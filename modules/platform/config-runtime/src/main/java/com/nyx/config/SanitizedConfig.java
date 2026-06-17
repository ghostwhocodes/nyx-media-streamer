package com.nyx.config;

import java.util.List;

public record SanitizedConfig(
    String host,
    int port,
    List<String> corsOrigins,
    List<SanitizedMediaRoot> mediaRoots,
    SanitizedAuth auth,
    SanitizedTranscode transcode,
    SanitizedThumbnails thumbnails,
    SanitizedQuota quota,
    SanitizedRateLimit rateLimit,
    SanitizedCsrf csrf,
    SanitizedTls tls,
    SanitizedWebhooks webhooks,
    SanitizedBackup backup
) {
    public SanitizedConfig {
        corsOrigins = List.copyOf(corsOrigins);
        mediaRoots = List.copyOf(mediaRoots);
    }

    public SanitizedConfig(
        String host,
        int port,
        List<String> corsOrigins,
        List<SanitizedMediaRoot> mediaRoots,
        SanitizedAuth auth,
        SanitizedTranscode transcode,
        SanitizedThumbnails thumbnails,
        SanitizedQuota quota
    ) {
        this(
            host,
            port,
            corsOrigins,
            mediaRoots,
            auth,
            transcode,
            thumbnails,
            quota,
            new SanitizedRateLimit(),
            new SanitizedCsrf(),
            new SanitizedTls(),
            new SanitizedWebhooks(),
            new SanitizedBackup()
        );
    }
}
