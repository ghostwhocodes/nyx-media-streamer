package com.nyx.playback.contracts;

import com.nyx.media.contracts.UserMediaState;

public interface MediaSessionReportService {
    MediaSessionReportResult reportPlayback(
        String sessionId,
        MediaSessionPlaybackReport report,
        String authenticatedUserId
    );
}
