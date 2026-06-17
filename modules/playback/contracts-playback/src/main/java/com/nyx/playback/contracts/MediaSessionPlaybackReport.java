package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;

public record MediaSessionPlaybackReport(
    MediaSessionPlaybackEvent event,
    String objectId,
    MediaKind mediaKind,
    Long positionMillis,
    Long durationMillis,
    String occurredAt,
    String clientName,
    String deviceName,
    String playbackContext
) {
    public MediaSessionPlaybackReport(MediaSessionPlaybackEvent event) {
        this(event, null, null, null, null, null, null, null, null);
    }
}
