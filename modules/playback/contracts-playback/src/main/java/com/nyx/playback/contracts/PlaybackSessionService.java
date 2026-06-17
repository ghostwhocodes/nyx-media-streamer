package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.nio.file.Path;

public interface PlaybackSessionService {
    PlaybackSession openSession(PlaybackRequest request, String owner);

    PlaybackSession getSession(String sessionId, String owner);

    PlaybackSession reportPlayback(String sessionId, MediaSessionPlaybackReport report, String owner);

    String getSessionJobId(String sessionId, String owner);

    void closeSession(String sessionId, String owner);

    String getHlsManifest(String sessionId, String owner);

    String getDashManifest(String sessionId, String owner);

    Path getDirectContentPath(String sessionId, String owner);
}
