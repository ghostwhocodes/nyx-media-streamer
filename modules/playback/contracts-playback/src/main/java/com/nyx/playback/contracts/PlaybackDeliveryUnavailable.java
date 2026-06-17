package com.nyx.playback.contracts;

public record PlaybackDeliveryUnavailable(
    PlaybackSession session,
    PlaybackDeliveryRequirement requirement,
    String message
) implements PlaybackDeliveryOutcome {
}
