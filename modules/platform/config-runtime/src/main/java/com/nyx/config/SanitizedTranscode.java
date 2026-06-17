package com.nyx.config;

public record SanitizedTranscode(
    String defaultFormat,
    int maxConcurrentJobs,
    int segmentCacheGracePeriodMinutes
) {}
