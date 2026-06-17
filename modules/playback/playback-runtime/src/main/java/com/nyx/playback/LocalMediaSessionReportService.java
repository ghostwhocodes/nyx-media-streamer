package com.nyx.playback;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.playback.contracts.AudioSessionService;
import com.nyx.playback.contracts.MediaPlaystateProjector;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.nyx.playback.contracts.MediaSessionReportResult;
import com.nyx.playback.contracts.MediaSessionReportService;
import com.nyx.playback.contracts.PlaybackSessionService;
import java.util.Map;

public final class LocalMediaSessionReportService implements MediaSessionReportService {
    private final AudioSessionService audioSessionService;
    private final PlaybackSessionService playbackSessionService;
    private final MediaPlaystateProjector mediaPlaystateProjector;

    public LocalMediaSessionReportService(
        AudioSessionService audioSessionService,
        PlaybackSessionService playbackSessionService,
        MediaPlaystateProjector mediaPlaystateProjector
    ) {
        this.audioSessionService = audioSessionService;
        this.playbackSessionService = playbackSessionService;
        this.mediaPlaystateProjector = mediaPlaystateProjector;
    }

    @Override
    public MediaSessionReportResult reportPlayback(
        String sessionId,
        MediaSessionPlaybackReport report,
        String authenticatedUserId
    ) {
        return switch (resolveSessionType(sessionId)) {
            case PLAYBACK -> {
                var session = playbackSessionService.reportPlayback(sessionId, report, authenticatedUserId);
                projectDurableStateIfAuthenticated(authenticatedUserId, session.objectId() != null ? session.objectId() : report.objectId(), report);
                yield new MediaSessionReportResult.Playback(session);
            }
            case AUDIO -> {
                var session = audioSessionService.reportPlayback(sessionId, report, authenticatedUserId);
                projectDurableStateIfAuthenticated(authenticatedUserId, session.objectId() != null ? session.objectId() : report.objectId(), report);
                yield new MediaSessionReportResult.Audio(session);
            }
        };
    }

    private ResolvedSessionType resolveSessionType(String sessionId) {
        if (playbackSessionService.getSession(sessionId, null) != null) {
            return ResolvedSessionType.PLAYBACK;
        }
        if (audioSessionService.getSession(sessionId, null) != null) {
            return ResolvedSessionType.AUDIO;
        }
        sneakyThrow(new NyxException(ErrorCode.JOB_NOT_FOUND, "Media session not found: " + sessionId, Map.of(), null));
        return null;
    }

    private void projectDurableStateIfAuthenticated(
        String authenticatedUserId,
        String objectId,
        MediaSessionPlaybackReport report
    ) {
        if (authenticatedUserId == null || objectId == null) {
            return;
        }
        mediaPlaystateProjector.projectPlaybackState(authenticatedUserId, objectId, report);
    }

    private enum ResolvedSessionType {
        AUDIO,
        PLAYBACK
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
