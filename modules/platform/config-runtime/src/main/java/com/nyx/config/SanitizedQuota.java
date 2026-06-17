package com.nyx.config;

public record SanitizedQuota(
    boolean enabled,
    int defaultMaxConcurrentJobs,
    int defaultMaxRequestsPerMinute
) {}
