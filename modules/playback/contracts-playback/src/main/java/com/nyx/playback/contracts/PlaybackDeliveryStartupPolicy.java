package com.nyx.playback.contracts;

public record PlaybackDeliveryStartupPolicy(
    int pollAttempts,
    long pollDelayMillis,
    PlaybackDeliveryTimeoutAction timeoutAction,
    PlaybackDeliveryRetry retry
) {
    public PlaybackDeliveryStartupPolicy() {
        this(1, 0L, PlaybackDeliveryTimeoutAction.RETURN_PENDING, new PlaybackDeliveryRetry());
    }

    public PlaybackDeliveryStartupPolicy(int pollAttempts, long pollDelayMillis) {
        this(pollAttempts, pollDelayMillis, PlaybackDeliveryTimeoutAction.RETURN_PENDING, new PlaybackDeliveryRetry());
    }

    public PlaybackDeliveryStartupPolicy {
        if (pollAttempts < 1) {
            throw new IllegalArgumentException("pollAttempts must be positive");
        }
        if (pollDelayMillis < 0L) {
            throw new IllegalArgumentException("pollDelayMillis must not be negative");
        }
        timeoutAction = timeoutAction == null ? PlaybackDeliveryTimeoutAction.RETURN_PENDING : timeoutAction;
        retry = retry == null ? new PlaybackDeliveryRetry() : retry;
    }
}
