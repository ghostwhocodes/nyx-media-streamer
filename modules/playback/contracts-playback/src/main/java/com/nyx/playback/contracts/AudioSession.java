package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.nio.file.Path;

public record AudioSession(
    String sessionId,
    String objectId,
    MediaKind mediaKind,
    PlaybackSessionState state,
    AudioNegotiationRequest request,
    AudioNegotiationDecision decision,
    AudioSessionArtifacts artifacts,
    MediaSessionTelemetry telemetry,
    String failureCode,
    String failureMessage,
    String createdAt,
    PlaybackSessionLifecycle lifecycle
) {
}
