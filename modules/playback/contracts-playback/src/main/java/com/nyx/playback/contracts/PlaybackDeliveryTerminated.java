package com.nyx.playback.contracts;

public record PlaybackDeliveryTerminated(
    PlaybackSession session,
    PlaybackLifecyclePhase phase,
    String message
) implements PlaybackDeliveryOutcome {
}
