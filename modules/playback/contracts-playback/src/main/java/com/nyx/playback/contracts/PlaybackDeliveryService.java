package com.nyx.playback.contracts;

public interface PlaybackDeliveryService {
    PlaybackDeliveryOutcome open(PlaybackDeliveryRequest request);

    PlaybackDeliveryOutcome observe(PlaybackDeliverySessionRequest request);

    void close(String sessionId, String owner);
}
