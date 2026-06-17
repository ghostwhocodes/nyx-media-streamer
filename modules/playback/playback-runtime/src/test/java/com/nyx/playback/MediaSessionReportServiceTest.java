package com.nyx.playback;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.UserMediaState;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.AudioSessionService;
import com.nyx.playback.contracts.MediaPlaystateProjector;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.nyx.playback.contracts.MediaSessionReportResult;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.playback.contracts.PlaybackSessionState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.nyx.playback.PlaybackContractFactories.audioSession;
import static com.nyx.playback.PlaybackContractFactories.mediaSessionPlaybackReport;
import static com.nyx.playback.PlaybackContractFactories.playbackSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaSessionReportServiceTest {
    @Test
    void sharedReportServiceRoutesPlaybackSessionsThroughPlaybackRuntimeAndProjectsDurableState() {
        var playbackSession = playbackSession(
            "playback-session-1",
            "object-video-1",
            MediaKind.VIDEO,
            PlaybackSessionState.READY,
            null,
            null,
            null,
            null,
            null,
            "2026-04-12T12:00:00Z",
            null
        );
        StubPlaybackSessionService playbackService = new StubPlaybackSessionService(playbackSession);
        StubAudioSessionService audioService = new StubAudioSessionService(null);
        RecordingMediaPlaystateProjector projector = new RecordingMediaPlaystateProjector();
        LocalMediaSessionReportService service = new LocalMediaSessionReportService(audioService, playbackService, projector);

        var result = service.reportPlayback(
            playbackSession.sessionId(),
            mediaSessionPlaybackReport(MediaSessionPlaybackEvent.HEARTBEAT, null, null, 50_000L, 100_000L, null, null, null, null),
            "alice"
        );

        MediaSessionReportResult.Playback routed = assertInstanceOf(MediaSessionReportResult.Playback.class, result);
        assertEquals(playbackSession.sessionId(), routed.session().sessionId());
        assertEquals(List.of("alice"), playbackService.reportOwners);
        assertTrue(audioService.reportOwners.isEmpty());
        assertEquals(1, projector.requests.size());
        assertEquals("alice", projector.requests.getFirst().userId);
        assertEquals("object-video-1", projector.requests.getFirst().objectId);
    }

    @Test
    void sharedReportServiceRoutesAudioSessionsThroughAudioRuntimeAndSkipsDurableProjectionForAnonymousUsers() {
        var audioSession = audioSession(
            "audio-session-1",
            "object-audio-1",
            MediaKind.AUDIO,
            PlaybackSessionState.READY,
            null,
            null,
            null,
            null,
            null,
            null,
            "2026-04-12T12:00:00Z",
            null
        );
        StubPlaybackSessionService playbackService = new StubPlaybackSessionService(null);
        StubAudioSessionService audioService = new StubAudioSessionService(audioSession);
        RecordingMediaPlaystateProjector projector = new RecordingMediaPlaystateProjector();
        LocalMediaSessionReportService service = new LocalMediaSessionReportService(audioService, playbackService, projector);

        var result = service.reportPlayback(
            audioSession.sessionId(),
            mediaSessionPlaybackReport(MediaSessionPlaybackEvent.STOPPED, null, null, 80_000L, 100_000L, null, null, null, null),
            null
        );

        MediaSessionReportResult.Audio routed = assertInstanceOf(MediaSessionReportResult.Audio.class, result);
        assertEquals(audioSession.sessionId(), routed.session().sessionId());
        assertEquals(Collections.singletonList(null), audioService.reportOwners);
        assertTrue(playbackService.reportOwners.isEmpty());
        assertTrue(projector.requests.isEmpty());
    }

    @Test
    void sharedReportServiceReturnsJobNotFoundWhenSessionIdDoesNotMatchAudioOrPlaybackRuntime() {
        LocalMediaSessionReportService service = new LocalMediaSessionReportService(
            new StubAudioSessionService(null),
            new StubPlaybackSessionService(null),
            new RecordingMediaPlaystateProjector()
        );

        NyxException error = assertThrows(
            NyxException.class,
            () -> service.reportPlayback(
                "missing-session",
                mediaSessionPlaybackReport(MediaSessionPlaybackEvent.HEARTBEAT, null, null, null, null, null, null, null, null),
                "alice"
            )
        );

        assertEquals(ErrorCode.JOB_NOT_FOUND, error.getErrorCode());
    }

    private static final class RecordingMediaPlaystateProjector implements MediaPlaystateProjector {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public UserMediaState projectPlaybackState(String userId, String objectId, MediaSessionPlaybackReport report) {
            requests.add(new Request(userId, objectId, report));
            return new UserMediaState(
                userId,
                objectId,
                report.positionMillis(),
                false,
                null,
                false,
                null,
                0,
                null,
                report.occurredAt() == null ? "2026-04-12T12:00:00Z" : report.occurredAt()
            );
        }

        private record Request(
            String userId,
            String objectId,
            MediaSessionPlaybackReport report
        ) {
        }
    }

    private static final class StubPlaybackSessionService implements PlaybackSessionService {
        private final com.nyx.playback.contracts.PlaybackSession reportedSession;
        private final List<String> reportOwners = new ArrayList<>();

        private StubPlaybackSessionService(com.nyx.playback.contracts.PlaybackSession reportedSession) {
            this.reportedSession = reportedSession;
        }

        @Override
        public com.nyx.playback.contracts.PlaybackSession openSession(PlaybackRequest request, String owner) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public com.nyx.playback.contracts.PlaybackSession getSession(String sessionId, String owner) {
            return reportedSession != null && reportedSession.sessionId().equals(sessionId) ? reportedSession : null;
        }

        @Override
        public com.nyx.playback.contracts.PlaybackSession reportPlayback(
            String sessionId,
            MediaSessionPlaybackReport report,
            String owner
        ) {
            reportOwners.add(owner);
            if (reportedSession != null && reportedSession.sessionId().equals(sessionId)) {
                return reportedSession;
            }
            throw sneakyThrow(new NyxException(ErrorCode.JOB_NOT_FOUND, "Playback session not found: " + sessionId));
        }

        @Override
        public String getSessionJobId(String sessionId, String owner) {
            return null;
        }

        @Override
        public void closeSession(String sessionId, String owner) {
        }

        @Override
        public String getHlsManifest(String sessionId, String owner) {
            return null;
        }

        @Override
        public String getDashManifest(String sessionId, String owner) {
            return null;
        }

        @Override
        public Path getDirectContentPath(String sessionId, String owner) {
            return null;
        }
    }

    private static final class StubAudioSessionService implements AudioSessionService {
        private final com.nyx.playback.contracts.AudioSession reportedSession;
        private final List<String> reportOwners = new ArrayList<>();

        private StubAudioSessionService(com.nyx.playback.contracts.AudioSession reportedSession) {
            this.reportedSession = reportedSession;
        }

        @Override
        public com.nyx.playback.contracts.AudioSession openSession(AudioNegotiationRequest request, String owner) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public com.nyx.playback.contracts.AudioSession getSession(String sessionId, String owner) {
            return reportedSession != null && reportedSession.sessionId().equals(sessionId) ? reportedSession : null;
        }

        @Override
        public AudioNegotiationRequest getSessionRequest(String sessionId, String owner) {
            return null;
        }

        @Override
        public com.nyx.playback.contracts.AudioSession reportPlayback(
            String sessionId,
            MediaSessionPlaybackReport report,
            String owner
        ) {
            reportOwners.add(owner);
            if (reportedSession != null && reportedSession.sessionId().equals(sessionId)) {
                return reportedSession;
            }
            throw sneakyThrow(new NyxException(ErrorCode.JOB_NOT_FOUND, "Audio session not found: " + sessionId));
        }

        @Override
        public void closeSession(String sessionId, String owner) {
        }

        @Override
        public Path getSourcePath(String sessionId, String owner) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
