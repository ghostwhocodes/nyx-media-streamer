package com.nyx.config;

public record SanitizedRateLimit(
    boolean enabled,
    int requestsPerSecond,
    long windowSeconds,
    int burstSize
) {
    public SanitizedRateLimit() {
        this(false, 100, 1L, 200);
    }
}
