package com.nyx.config;

import java.util.Objects;

public record TranscodeConfig(
    String defaultFormat,
    int segmentCacheGracePeriodMinutes,
    int segmentDurationSteadyStateSecs,
    int segmentCacheMaxEntries,
    long minFreeDiskBytes,
    int maxRetries,
    long retryBackoffMs,
    int circuitBreakerThreshold
) {
    public TranscodeConfig {
        defaultFormat = Objects.requireNonNull(defaultFormat, "defaultFormat");
    }

    public TranscodeConfig(String defaultFormat, int segmentCacheGracePeriodMinutes, int segmentDurationSteadyStateSecs) {
        this(defaultFormat, segmentCacheGracePeriodMinutes, segmentDurationSteadyStateSecs, 10_000, 524_288_000L, 3, 2_000L, 5);
    }

    public String getDefaultFormat() {
        return defaultFormat;
    }

    public int getSegmentCacheGracePeriodMinutes() {
        return segmentCacheGracePeriodMinutes;
    }

    public int getSegmentDurationSteadyStateSecs() {
        return segmentDurationSteadyStateSecs;
    }

    public int getSegmentCacheMaxEntries() {
        return segmentCacheMaxEntries;
    }

    public long getMinFreeDiskBytes() {
        return minFreeDiskBytes;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public int getCircuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }
}
