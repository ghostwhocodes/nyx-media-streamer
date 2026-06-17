package com.nyx.playback;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.media.contracts.MediaKind;
import com.nyx.playback.contracts.AudioNegotiationDecision;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.AudioNegotiationService;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.nyx.playback.contracts.PlaybackLifecycleEndReason;
import com.nyx.playback.contracts.PlaybackLifecyclePhase;
import com.nyx.playback.contracts.PlaybackSessionState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.nyx.playback.PlaybackContractFactories.audioFormatDescriptor;
import static com.nyx.playback.PlaybackContractFactories.audioNegotiationDecision;
import static com.nyx.playback.PlaybackContractFactories.audioNegotiationRequest;
import static com.nyx.playback.PlaybackContractFactories.mediaSessionPlaybackReport;
import static com.nyx.playback.PlaybackContractFactories.mediaSourceRef;
import static com.nyx.playback.PlaybackContractFactories.playbackSourceAudioStream;
import static com.nyx.playback.PlaybackContractFactories.playbackSourceCharacteristics;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioSessionRuntimeTest {
    @Test
    void audioSessionOpensReadyForDirectDelivery() {
        FakeAudioNegotiationService negotiationService = new FakeAudioNegotiationService(
            audioNegotiationDecision(
                com.nyx.playback.contracts.AudioDeliveryMode.DIRECT_PLAY,
                null,
                null,
                audioFormatDescriptor("flac", "flac", "audio/flac", null, null, null)
            )
        );
        LocalAudioSessionService sessionService = new LocalAudioSessionService(negotiationService);

        var session = sessionService.openSession(
            audioNegotiationRequest(
                mediaSourceRef(
                    "/media/album.flac",
                    null,
                    "object-audio-runtime-1",
                    MediaKind.AUDIO
                ),
                0L,
                null,
                null,
                null,
                null
            ),
            "alice"
        );

        assertEquals(PlaybackSessionState.READY, session.state());
        assertEquals("object-audio-runtime-1", session.objectId());
        assertEquals(MediaKind.AUDIO, session.mediaKind());
        assertEquals(com.nyx.playback.contracts.AudioDeliveryMode.DIRECT_PLAY, session.decision().mode());
        assertEquals(PlaybackLifecyclePhase.READY, session.lifecycle().phase());
        assertEquals(0.0, session.lifecycle().progressPercent());
        assertEquals("object-audio-runtime-1", session.telemetry().objectId());
        assertEquals(MediaKind.AUDIO, session.telemetry().mediaKind());
        assertTrue(session.artifacts().contentUrl().endsWith("/content"));
        assertEquals(Path.of("/media/album.flac"), sessionService.getSourcePath(session.sessionId(), "alice"));
        assertEquals(1, negotiationService.requests.size());

        sessionService.shutdown();
    }

    @Test
    void audioSessionOpensReadyForTranscodeBackedDeliveryAndPreservesRequestContext() {
        FakeAudioNegotiationService negotiationService = new FakeAudioNegotiationService(
            audioNegotiationDecision(
                com.nyx.playback.contracts.AudioDeliveryMode.TRANSCODE,
                null,
                null,
                audioFormatDescriptor("adts", "aac", "audio/aac", 192, null, null)
            )
        );
        LocalAudioSessionService sessionService = new LocalAudioSessionService(negotiationService);
        var request = audioNegotiationRequest(
            mediaSourceRef("/media/song.flac"),
            15_000L,
            null,
            null,
            null,
            null
        );

        var session = sessionService.openSession(request, "alice");
        var resolvedRequest = sessionService.getSessionRequest(session.sessionId(), "alice");

        assertEquals(PlaybackSessionState.READY, session.state());
        assertEquals(com.nyx.playback.contracts.AudioDeliveryMode.TRANSCODE, session.decision().mode());
        assertEquals(15_000L, resolvedRequest.startPositionMillis());
        assertEquals("/media/song.flac", resolvedRequest.source().path());

        sessionService.shutdown();
    }

    @Test
    void audioSessionPlaybackReportsUpdateTelemetryAndLifecycleProgress() {
        FakeAudioNegotiationService negotiationService = new FakeAudioNegotiationService(
            audioNegotiationDecision(
                com.nyx.playback.contracts.AudioDeliveryMode.DIRECT_PLAY,
                null,
                null,
                audioFormatDescriptor("mp3", "mp3", "audio/mpeg", null, null, null)
            )
        );
        LocalAudioSessionService sessionService = new LocalAudioSessionService(negotiationService);
        var request = audioNegotiationRequest(
            mediaSourceRef(
                "/media/session.mp3",
                playbackSourceCharacteristics(
                    "mp3",
                    200_000L,
                    null,
                    null,
                    List.of(playbackSourceAudioStream(0, "mp3", 2, null, null, null, null)),
                    null
                ),
                null,
                null
            ),
            0L,
            null,
            null,
            null,
            null
        );

        var session = sessionService.openSession(request, "alice");
        var started = sessionService.reportPlayback(
            session.sessionId(),
            mediaSessionPlaybackReport(MediaSessionPlaybackEvent.STARTED, null, null, 0L, null, null, null, null, null),
            "alice"
        );
        var updated = sessionService.reportPlayback(
            session.sessionId(),
            mediaSessionPlaybackReport(MediaSessionPlaybackEvent.HEARTBEAT, null, null, 50_000L, null, null, null, null, null),
            "alice"
        );

        assertEquals(MediaSessionPlaybackEvent.STARTED, started.telemetry().lastEvent());
        assertEquals(MediaKind.AUDIO, started.telemetry().mediaKind());
        assertEquals(0L, started.telemetry().positionMillis());
        assertEquals(MediaSessionPlaybackEvent.HEARTBEAT, updated.telemetry().lastEvent());
        assertEquals(50_000L, updated.telemetry().positionMillis());
        assertEquals(200_000L, updated.telemetry().durationMillis());
        assertEquals(25.0, updated.telemetry().progressPercent());
        assertEquals(25.0, updated.lifecycle().progressPercent());

        sessionService.shutdown();
    }

    @Test
    void completedPlaybackReportClosesSessionAndRejectsLaterReports() {
        LocalAudioSessionService sessionService = new LocalAudioSessionService(
            new FakeAudioNegotiationService(
                audioNegotiationDecision(
                    com.nyx.playback.contracts.AudioDeliveryMode.TRANSCODE,
                    null,
                    null,
                    audioFormatDescriptor("adts", "aac", "audio/aac", null, null, null)
                )
            )
        );
        var request = audioNegotiationRequest(
            mediaSourceRef(
                "/media/complete.flac",
                playbackSourceCharacteristics(
                    "flac",
                    180_000L,
                    null,
                    null,
                    List.of(playbackSourceAudioStream(0, "flac", 2, null, null, null, null)),
                    null
                ),
                null,
                null
            ),
            0L,
            null,
            null,
            null,
            null
        );

        var session = sessionService.openSession(request, "alice");
        var completed = sessionService.reportPlayback(
            session.sessionId(),
            mediaSessionPlaybackReport(MediaSessionPlaybackEvent.COMPLETED, null, null, null, 180_000L, null, null, null, null),
            "alice"
        );

        assertEquals(PlaybackSessionState.CLOSED, completed.state());
        assertEquals(PlaybackLifecyclePhase.STOPPED, completed.lifecycle().phase());
        assertEquals(PlaybackLifecycleEndReason.PLAYBACK_COMPLETED, completed.lifecycle().endReason());
        assertEquals(MediaSessionPlaybackEvent.COMPLETED, completed.telemetry().lastEvent());
        assertEquals(100.0, completed.telemetry().progressPercent());
        assertEquals(MediaKind.AUDIO, completed.mediaKind());
        assertNull(completed.artifacts());

        NyxException error = assertThrows(
            NyxException.class,
            () -> sessionService.reportPlayback(
                session.sessionId(),
                mediaSessionPlaybackReport(MediaSessionPlaybackEvent.HEARTBEAT, null, null, 1_000L, null, null, null, null, null),
                "alice"
            )
        );
        assertEquals(ErrorCode.INVALID_REQUEST, error.getErrorCode());

        sessionService.shutdown();
    }

    @Test
    void audioSessionOwnerVisibilityAndCloseSemanticsMatchPlaybackSessionExpectations() {
        LocalAudioSessionService sessionService = new LocalAudioSessionService(
            new FakeAudioNegotiationService(
                audioNegotiationDecision(
                    com.nyx.playback.contracts.AudioDeliveryMode.TRANSCODE,
                    null,
                    null,
                    audioFormatDescriptor("opus", "opus", "audio/opus", null, null, null)
                )
            )
        );

        var session = sessionService.openSession(
            audioNegotiationRequest(mediaSourceRef("/media/song.flac"), 0L, null, null, null, null),
            "alice"
        );

        assertNull(sessionService.getSession(session.sessionId(), "bob"));
        NyxException error = assertThrows(NyxException.class, () -> sessionService.closeSession(session.sessionId(), "bob"));
        assertEquals(ErrorCode.PATH_NOT_ALLOWED, error.getErrorCode());

        sessionService.closeSession(session.sessionId(), "alice");
        var closed = sessionService.getSession(session.sessionId(), "alice");

        assertEquals(PlaybackSessionState.CLOSED, closed.state());
        assertEquals(PlaybackLifecyclePhase.STOPPED, closed.lifecycle().phase());
        assertEquals(MediaSessionPlaybackEvent.STOPPED, closed.telemetry().lastEvent());
        assertEquals(PlaybackLifecycleEndReason.CLIENT_REQUESTED, closed.lifecycle().endReason());
        assertNull(sessionService.getSourcePath(session.sessionId(), "alice"));

        sessionService.shutdown();
    }

    private static final class FakeAudioNegotiationService implements AudioNegotiationService {
        private final AudioNegotiationDecision decision;
        private final List<AudioNegotiationRequest> requests = new ArrayList<>();

        private FakeAudioNegotiationService(AudioNegotiationDecision decision) {
            this.decision = decision;
        }

        @Override
        public AudioNegotiationDecision decide(AudioNegotiationRequest request) {
            requests.add(request);
            return decision;
        }
    }
}
