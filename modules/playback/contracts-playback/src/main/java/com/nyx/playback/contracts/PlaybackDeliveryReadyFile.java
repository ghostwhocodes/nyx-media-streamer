package com.nyx.playback.contracts;

import java.nio.file.Path;

public record PlaybackDeliveryReadyFile(
    PlaybackSession session,
    Path path
) implements PlaybackDeliveryOutcome {
}
