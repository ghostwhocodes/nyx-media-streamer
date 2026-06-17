package com.nyx.playback.contracts;

public record PlaybackDeliveryRetry(
    int retryAfterSeconds,
    String status
) {
    public PlaybackDeliveryRetry() {
        this(2, "pending");
    }

    public PlaybackDeliveryRetry {
        if (retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds must not be negative");
        }
        status = status == null || status.isBlank() ? "pending" : status;
    }
}
