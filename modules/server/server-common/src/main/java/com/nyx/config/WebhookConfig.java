package com.nyx.config;

import java.util.Set;

public record WebhookConfig(
    boolean enabled,
    int maxRetries,
    long retryBackoffMs,
    long timeoutMs,
    int maxConcurrentDeliveries,
    Set<String> allowedHosts,
    int deliveryRetentionDays,
    int cleanupIntervalMinutes
) {
    public WebhookConfig {
        allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
    }

    public WebhookConfig() {
        this(false, 3, 5_000L, 30_000L, 10, Set.of(), 7, 60);
    }

    public boolean getEnabled() {
        return enabled;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public int getMaxConcurrentDeliveries() {
        return maxConcurrentDeliveries;
    }

    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    public int getDeliveryRetentionDays() {
        return deliveryRetentionDays;
    }

    public int getCleanupIntervalMinutes() {
        return cleanupIntervalMinutes;
    }
}
