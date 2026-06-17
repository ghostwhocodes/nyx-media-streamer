package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;

public record MediaSessionTelemetry(
    String objectId,
    MediaKind mediaKind,
    MediaSessionPlaybackEvent lastEvent,
    String lastEventAt,
    Long positionMillis,
    Long durationMillis,
    Double progressPercent,
    String clientName,
    String deviceName,
    String playbackContext
) {
}
