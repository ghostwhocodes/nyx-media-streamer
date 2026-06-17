package com.nyx.config;

public record UserQuotaOverride(
    Integer maxConcurrentJobs,
    Integer maxRequestsPerMinute,
    Long maxStorageBytes
) {
    public UserQuotaOverride() {
        this(null, null, null);
    }

    public Integer getMaxConcurrentJobs() {
        return maxConcurrentJobs;
    }

    public Integer getMaxRequestsPerMinute() {
        return maxRequestsPerMinute;
    }

    public Long getMaxStorageBytes() {
        return maxStorageBytes;
    }
}
