package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.nio.file.Path;

public record PlaybackSession(
    String sessionId,
    String objectId,
    MediaKind mediaKind,
    PlaybackSessionState state,
    PlaybackDecision decision,
    PlaybackSessionArtifacts artifacts,
    MediaSessionTelemetry telemetry,
    String failureCode,
    String failureMessage,
    String createdAt,
    PlaybackSessionLifecycle lifecycle
) {
}
