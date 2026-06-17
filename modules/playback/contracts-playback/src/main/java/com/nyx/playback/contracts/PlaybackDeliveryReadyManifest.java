package com.nyx.playback.contracts;

import com.nyx.stream.representation.contracts.StreamingProtocol;

public record PlaybackDeliveryReadyManifest(
    PlaybackSession session,
    StreamingProtocol protocol,
    String manifest,
    String backingJobId
) implements PlaybackDeliveryOutcome {
}
