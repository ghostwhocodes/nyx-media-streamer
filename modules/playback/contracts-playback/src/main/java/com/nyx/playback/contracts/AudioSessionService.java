package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.nio.file.Path;

public interface AudioSessionService {
    AudioSession openSession(AudioNegotiationRequest request, String owner);

    AudioSession getSession(String sessionId, String owner);

    AudioNegotiationRequest getSessionRequest(String sessionId, String owner);

    AudioSession reportPlayback(String sessionId, MediaSessionPlaybackReport report, String owner);

    void closeSession(String sessionId, String owner);

    Path getSourcePath(String sessionId, String owner);
}
