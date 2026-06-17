package com.nyx.config;

public record SanitizedWebhooks(
    boolean enabled,
    int maxRetries,
    int deliveryRetentionDays
) {
    public SanitizedWebhooks() {
        this(false, 3, 7);
    }
}
