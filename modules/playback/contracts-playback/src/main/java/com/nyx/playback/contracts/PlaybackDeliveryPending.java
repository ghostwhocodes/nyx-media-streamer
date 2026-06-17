package com.nyx.playback.contracts;

public record PlaybackDeliveryPending(
    PlaybackSession session,
    PlaybackDeliveryRetry retry
) implements PlaybackDeliveryOutcome {
    public PlaybackDeliveryPending {
        retry = retry == null ? new PlaybackDeliveryRetry() : retry;
    }
}
