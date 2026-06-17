package com.nyx.config;

public record RateLimitConfig(
    boolean enabled,
    int requestsPerSecond,
    long windowSeconds,
    int burstSize
) {
    public RateLimitConfig() {
        this(false, 100, 1L, 200);
    }

    public boolean getEnabled() {
        return enabled;
    }

    public int getRequestsPerSecond() {
        return requestsPerSecond;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public int getBurstSize() {
        return burstSize;
    }
}
