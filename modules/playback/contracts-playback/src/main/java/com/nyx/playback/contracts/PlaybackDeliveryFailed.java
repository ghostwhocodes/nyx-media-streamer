package com.nyx.playback.contracts;

public record PlaybackDeliveryFailed(
    PlaybackSession session,
    String failureCode,
    String message
) implements PlaybackDeliveryOutcome {
}
