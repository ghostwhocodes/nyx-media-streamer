package com.nyx.playback.contracts;

public sealed interface PlaybackDeliveryOutcome permits
    PlaybackDeliveryReadyManifest,
    PlaybackDeliveryReadyFile,
    PlaybackDeliveryUnavailable,
    PlaybackDeliveryPending,
    PlaybackDeliveryFailed,
    PlaybackDeliveryTerminated {
    PlaybackSession session();

    default String sessionId() {
        PlaybackSession currentSession = session();
        return currentSession == null ? null : currentSession.sessionId();
    }
}
