package com.nyx.config;

import java.util.Map;

public record QuotaConfig(
    boolean enabled,
    int defaultMaxConcurrentJobs,
    int defaultMaxRequestsPerMinute,
    long defaultMaxStorageBytes,
    Map<String, UserQuotaOverride> userOverrides
) {
    public QuotaConfig {
        userOverrides = userOverrides == null ? Map.of() : Map.copyOf(userOverrides);
    }

    public QuotaConfig() {
        this(false, 4, 60, 10_737_418_240L, Map.of());
    }

    public boolean getEnabled() {
        return enabled;
    }

    public int getDefaultMaxConcurrentJobs() {
        return defaultMaxConcurrentJobs;
    }

    public int getDefaultMaxRequestsPerMinute() {
        return defaultMaxRequestsPerMinute;
    }

    public long getDefaultMaxStorageBytes() {
        return defaultMaxStorageBytes;
    }

    public Map<String, UserQuotaOverride> getUserOverrides() {
        return userOverrides;
    }
}
