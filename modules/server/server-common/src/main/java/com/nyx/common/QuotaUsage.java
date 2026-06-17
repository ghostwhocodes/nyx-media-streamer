package com.nyx.common;

public record QuotaUsage(
    String userId,
    int activeJobs,
    int maxConcurrentJobs,
    int requestsInWindow,
    int maxRequestsPerMinute,
    long storageUsedBytes,
    long maxStorageBytes
) {
    public QuotaUsage(
        String userId,
        int activeJobs,
        int maxConcurrentJobs,
        int requestsInWindow,
        int maxRequestsPerMinute
    ) {
        this(userId, activeJobs, maxConcurrentJobs, requestsInWindow, maxRequestsPerMinute, 0L, 0L);
    }
}
