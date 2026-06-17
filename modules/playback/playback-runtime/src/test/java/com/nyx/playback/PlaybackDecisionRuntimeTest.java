package com.nyx.playback;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.SubtitleStream;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.media.contracts.MediaKind;
import com.nyx.playback.contracts.AudioConstraint;
import com.nyx.playback.contracts.AudioTrackSelectionMode;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackDecisionService;
import com.nyx.playback.contracts.PlaybackLifecycleEndReason;
import com.nyx.playback.contracts.PlaybackLifecyclePhase;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackReason;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSessionState;
import com.nyx.playback.contracts.PlaybackOutputPreferences;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.RepresentationConstraint;
import com.nyx.playback.contracts.SubtitleDelivery;
import com.nyx.playback.contracts.SubtitleSelectionMode;
import com.nyx.transcode.contracts.BatchCancelResponse;
import com.nyx.transcode.contracts.BatchStatusResponse;
import com.nyx.transcode.contracts.BatchSubmitResponse;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeApplicationService;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobListing;
import com.nyx.transcode.contracts.TranscodeRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.nyx.playback.PlaybackContractFactories.audioConstraint;
import static com.nyx.playback.PlaybackContractFactories.audioTrackSelection;
import static com.nyx.playback.PlaybackContractFactories.mediaSessionPlaybackReport;
import static com.nyx.playback.PlaybackContractFactories.mediaSourceRef;
import static com.nyx.playback.PlaybackContractFactories.playbackCapabilitySet;
import static com.nyx.playback.PlaybackContractFactories.playbackClientProfile;
import static com.nyx.playback.PlaybackContractFactories.playbackConstraints;
import static com.nyx.playback.PlaybackContractFactories.playbackDecision;
import static com.nyx.playback.PlaybackContractFactories.playbackOutputPreferences;
import static com.nyx.playback.PlaybackContractFactories.playbackRequest;
import static com.nyx.playback.PlaybackContractFactories.playbackSelection;
import static com.nyx.playback.PlaybackContractFactories.playbackSourceAudioStream;
import static com.nyx.playback.PlaybackContractFactories.playbackSourceCharacteristics;
import static com.nyx.playback.PlaybackContractFactories.playbackSourceSubtitleStream;
import static com.nyx.playback.PlaybackContractFactories.playbackSourceVideoStream;
import static com.nyx.playback.PlaybackContractFactories.streamDescriptor;
import static com.nyx.playback.PlaybackContractFactories.subtitleSelection;
import static com.nyx.playback.PlaybackContractFactories.transcodePreferences;
import static com.nyx.playback.PlaybackContractFactories.videoConstraint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackDecisionRuntimeTest {
    @Test
    void decisionChoosesDirectPlayWhenFileDeliveryIsAllowedAndCompatible() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(new FakeMediaProber(videoProbe()));

        PlaybackDecision decision = service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mp4"),
                0L,
                playbackOutputPreferences(Set.of(StreamingProtocol.FILE), StreamingProtocol.FILE, false),
                null,
                playbackCapabilitySet(Set.of("mp4"), Set.of("h264"), Set.of("aac"), null, true, true, true, true, true),
                null,
                null,
                null
            )
        );

        assertEquals(PlaybackMode.DIRECT_PLAY, decision.mode());
        assertEquals(StreamingProtocol.FILE, decision.stream().protocol());
        assertTrue(decision.videoPreserved());
        assertTrue(decision.audioPreserved());
        assertEquals("mp4", decision.stream().container());
    }

    @Test
    void decisionChoosesRemuxForPackagedHlsDeliveryWhenStreamsAreAlreadyCompatible() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(new FakeMediaProber(videoProbe()));

        PlaybackDecision decision = service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mp4"),
                0L,
                playbackOutputPreferences(Set.of(StreamingProtocol.HLS), StreamingProtocol.HLS, true),
                null,
                playbackCapabilitySet(Set.of("mp4"), Set.of("h264"), Set.of("aac"), null, true, true, true, true, true),
                null,
                null,
                null
            )
        );

        assertEquals(PlaybackMode.REMUX, decision.mode());
        assertEquals(StreamingProtocol.HLS, decision.stream().protocol());
        assertTrue(decision.reasons().contains(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED));
        assertTrue(decision.videoPreserved());
        assertTrue(decision.audioPreserved());
    }

    @Test
    void decisionHonorsRequestedMpegTsHlsPackagingStrategy() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(new FakeMediaProber(videoProbe()));

        PlaybackDecision decision = service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mp4"),
                0L,
                new PlaybackOutputPreferences(
                    Set.of(StreamingProtocol.HLS),
                    StreamingProtocol.HLS,
                    true,
                    StreamRepresentation.HLS_MPEG_TS
                ),
                null,
                playbackCapabilitySet(Set.of("mp4"), Set.of("h264"), Set.of("aac"), null, true, true, true, true, true),
                null,
                null,
                null
            )
        );

        assertEquals(PlaybackMode.REMUX, decision.mode());
        assertEquals(StreamingProtocol.HLS, decision.stream().protocol());
        assertEquals("mpegts", decision.stream().container());
        assertEquals(StreamRepresentation.HLS_MPEG_TS, decision.stream().representation());
    }

    @Test
    void decisionRejectsAdaptiveMpegTsHlsPackagingStrategyForVideoTranscode() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(
            new FakeMediaProber(videoProbe("hevc", "aac", 2, "webvtt", "mp4"))
        );

        NyxException error = assertThrows(NyxException.class, () -> service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mp4"),
                0L,
                new PlaybackOutputPreferences(
                    Set.of(StreamingProtocol.HLS),
                    StreamingProtocol.HLS,
                    true,
                    StreamRepresentation.HLS_MPEG_TS
                ),
                null,
                playbackCapabilitySet(Set.of("mp4"), Set.of("h264"), Set.of("aac"), null, true, true, true, true, true),
                null,
                null,
                transcodePreferences(
                    null,
                    null,
                    List.of(
                        new RepresentationConstraint(854, 480, 1_500),
                        new RepresentationConstraint(1280, 720, 3_000)
                    )
                )
            )
        ));

        assertEquals(ErrorCode.INVALID_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("Adaptive HLS MPEG-TS output is not supported"));
    }

    @Test
    void decisionRejectsMpegTsHlsPackagingWhenProfileExpandsIntoAdaptiveLadder() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(
            new FakeMediaProber(videoProbe("hevc", "aac", 2, "webvtt", "mp4"))
        );

        NyxException error = assertThrows(NyxException.class, () -> service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mp4"),
                0L,
                new PlaybackOutputPreferences(
                    Set.of(StreamingProtocol.HLS),
                    StreamingProtocol.HLS,
                    true,
                    StreamRepresentation.HLS_MPEG_TS
                ),
                null,
                playbackCapabilitySet(Set.of("mp4"), Set.of("h264"), Set.of("aac"), null, true, true, true, true, true),
                null,
                null,
                transcodePreferences(
                    "adaptive_h264",
                    null,
                    List.of()
                )
            )
        ));

        assertEquals(ErrorCode.INVALID_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("Adaptive HLS MPEG-TS output is not supported"));
    }

    @Test
    void decisionAllowsAdaptiveProfileHintForMpegTsHlsAudioOnlyTranscode() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(
            new FakeMediaProber(videoProbe("h264", "dts", 2, "webvtt", "mp4"))
        );

        PlaybackDecision decision = service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mp4"),
                0L,
                new PlaybackOutputPreferences(
                    Set.of(StreamingProtocol.HLS),
                    StreamingProtocol.HLS,
                    true,
                    StreamRepresentation.HLS_MPEG_TS
                ),
                null,
                playbackCapabilitySet(Set.of("mp4"), Set.of("h264"), Set.of("aac"), null, true, true, true, true, true),
                null,
                null,
                transcodePreferences(
                    "adaptive_h264",
                    null,
                    List.of()
                )
            )
        );

        assertEquals(PlaybackMode.AUDIO_TRANSCODE, decision.mode());
        assertEquals(StreamRepresentation.HLS_MPEG_TS, decision.stream().representation());
        assertTrue(decision.videoPreserved());
        assertFalse(decision.audioPreserved());
    }

    @Test
    void decisionChoosesAudioTranscodeWhenVideoIsCompatibleButAudioIsNot() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(new FakeMediaProber(videoProbe("h264", "dts", 2, "webvtt", "mp4")));

        PlaybackDecision decision = service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mkv"),
                0L,
                playbackOutputPreferences(Set.of(StreamingProtocol.HLS), StreamingProtocol.HLS, true),
                null,
                playbackCapabilitySet(null, Set.of("h264"), Set.of("aac"), null, true, true, true, true, true),
                null,
                null,
                null
            )
        );

        assertEquals(PlaybackMode.AUDIO_TRANSCODE, decision.mode());
        assertTrue(decision.reasons().contains(PlaybackReason.AUDIO_CODEC_UNSUPPORTED));
        assertTrue(decision.videoPreserved());
        assertFalse(decision.audioPreserved());
        assertEquals(Set.of("aac"), decision.output().audioCodecs());
    }

    @Test
    void decisionChoosesVideoTranscodeWhenVideoCodecIsUnsupported() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(new FakeMediaProber(videoProbe("hevc", "aac", 2, "webvtt", "mp4")));

        PlaybackDecision decision = service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mkv"),
                0L,
                playbackOutputPreferences(Set.of(StreamingProtocol.HLS), StreamingProtocol.HLS, true),
                null,
                playbackCapabilitySet(null, Set.of("h264"), Set.of("aac"), null, true, true, true, true, true),
                null,
                null,
                null
            )
        );

        assertEquals(PlaybackMode.VIDEO_TRANSCODE, decision.mode());
        assertTrue(decision.reasons().contains(PlaybackReason.VIDEO_CODEC_UNSUPPORTED));
        assertFalse(decision.videoPreserved());
        assertEquals("h264", decision.output().videoCodec());
    }

    @Test
    void decisionRejectsDirectFileRepresentationWhenVideoTranscodeIsRequired() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(
            new FakeMediaProber(videoProbe("hevc", "aac", 2, "webvtt", "mp4"))
        );

        NyxException error = assertThrows(NyxException.class, () -> service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mp4"),
                0L,
                new PlaybackOutputPreferences(
                    Set.of(StreamingProtocol.FILE),
                    StreamingProtocol.FILE,
                    false,
                    StreamRepresentation.DIRECT_FILE
                ),
                null,
                playbackCapabilitySet(Set.of("mp4"), Set.of("h264"), Set.of("aac"), null, true, true, true, true, true),
                null,
                null,
                null
            )
        ));

        assertEquals(ErrorCode.INVALID_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("video transcode output"));
    }

    @Test
    void decisionChoosesSubtitleBurnInWhenSelectedSubtitleFormatIsUnsupported() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(new FakeMediaProber(videoProbe("h264", "aac", 2, "subrip", "mp4")));

        PlaybackDecision decision = service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mkv"),
                0L,
                playbackOutputPreferences(Set.of(StreamingProtocol.HLS), StreamingProtocol.HLS, true),
                null,
                playbackCapabilitySet(null, Set.of("h264"), Set.of("aac"), Set.of("webvtt"), true, true, true, true, true),
                playbackSelection(null, subtitleSelection(SubtitleSelectionMode.EXTRACT, 3)),
                null,
                null
            )
        );

        assertEquals(PlaybackMode.SUBTITLE_BURN_IN, decision.mode());
        assertTrue(decision.reasons().contains(PlaybackReason.SUBTITLE_BURN_IN_REQUIRED));
        assertEquals(SubtitleDelivery.BURN_IN, decision.subtitleDelivery());
        assertFalse(decision.videoPreserved());
    }

    @Test
    void decisionRejectsInvalidSelectedAudioTrack() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(new FakeMediaProber(videoProbe()));

        NyxException error = assertThrows(
            NyxException.class,
            () -> service.decide(
                playbackRequest(
                    mediaSourceRef("/media/test.mp4"),
                    0L,
                    null,
                    null,
                    null,
                    playbackSelection(audioTrackSelection(AudioTrackSelectionMode.SPECIFIC, List.of(99)), null),
                    null,
                    null
                )
            )
        );

        assertEquals(ErrorCode.INVALID_REQUEST, error.getErrorCode());
    }

    @Test
    void decisionUsesExplicitSourceCharacteristicsWhenProvidedOnTheRequest() {
        AtomicInteger probeCachedCalls = new AtomicInteger();
        MediaProber prober = new MediaProber() {
            @Override
            public ProbeResult probe(Path path) {
                throw new AssertionError("probe should not be used when characteristics are provided");
            }

            @Override
            public ProbeResult probeCached(Path path) {
                probeCachedCalls.incrementAndGet();
                throw new AssertionError("probeCached should not be used when characteristics are provided");
            }

            @Override
            public void clearCache() {
            }
        };
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(prober);

        PlaybackDecision decision = service.decide(
            playbackRequest(
                mediaSourceRef(
                    "/media/test.mkv",
                    playbackSourceCharacteristics(
                        "mkv",
                        null,
                        null,
                        List.of(playbackSourceVideoStream(0, "h264", 1920, 1080, 24.0, 4_000)),
                        List.of(playbackSourceAudioStream(1, "aac", 2, 192, null, null, null)),
                        List.of(playbackSourceSubtitleStream(3, "webvtt", "eng", null))
                    ),
                    null,
                    null
                ),
                0L,
                playbackOutputPreferences(Set.of(StreamingProtocol.FILE), StreamingProtocol.FILE, false),
                null,
                playbackCapabilitySet(Set.of("mkv"), Set.of("h264"), Set.of("aac"), Set.of("webvtt"), true, true, true, true, true),
                null,
                null,
                null
            )
        );

        assertEquals(PlaybackMode.DIRECT_PLAY, decision.mode());
        assertEquals("mkv", decision.stream().container());
        assertEquals(0, probeCachedCalls.get());
    }

    @Test
    void decisionUsesClientProfileResolutionCeilingsToChooseVideoTranscode() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(new FakeMediaProber(videoProbe()));

        PlaybackDecision decision = service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mp4"),
                0L,
                playbackOutputPreferences(Set.of(StreamingProtocol.HLS), StreamingProtocol.HLS, true),
                playbackClientProfile(
                    "mobile_hls",
                    playbackCapabilitySet(Set.of("mp4"), Set.of("h264"), Set.of("aac"), Set.of("webvtt"), true, true, true, true, true),
                    playbackConstraints(videoConstraint(1280, 720, null), null)
                ),
                null,
                null,
                null,
                null
            )
        );

        assertEquals(PlaybackMode.VIDEO_TRANSCODE, decision.mode());
        assertTrue(decision.reasons().contains(PlaybackReason.VIDEO_RESOLUTION_TOO_HIGH));
    }

    @Test
    void decisionUsesClientProfileSubtitleFormatLimitsToRequireBurnIn() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(new FakeMediaProber(videoProbe("h264", "aac", 2, "subrip", "mp4")));

        PlaybackDecision decision = service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mkv"),
                0L,
                playbackOutputPreferences(Set.of(StreamingProtocol.HLS), StreamingProtocol.HLS, true),
                playbackClientProfile(
                    "web_hls",
                    playbackCapabilitySet(null, Set.of("h264"), Set.of("aac"), Set.of("webvtt"), true, true, true, true, true),
                    null
                ),
                null,
                playbackSelection(null, subtitleSelection(SubtitleSelectionMode.EXTRACT, 3)),
                null,
                null
            )
        );

        assertEquals(PlaybackMode.SUBTITLE_BURN_IN, decision.mode());
        assertTrue(decision.reasons().contains(PlaybackReason.SUBTITLE_BURN_IN_REQUIRED));
        assertTrue(decision.reasons().contains(PlaybackReason.CLIENT_CAPABILITY_LIMIT));
    }

    @Test
    void decisionUsesClientProfileAudioChannelCeilingsToChooseAudioTranscode() {
        LocalPlaybackDecisionService service = new LocalPlaybackDecisionService(new FakeMediaProber(videoProbe("h264", "aac", 6, "webvtt", "mp4")));

        PlaybackDecision decision = service.decide(
            playbackRequest(
                mediaSourceRef("/media/test.mkv"),
                0L,
                playbackOutputPreferences(Set.of(StreamingProtocol.HLS), StreamingProtocol.HLS, true),
                playbackClientProfile(
                    "mobile_stereo",
                    playbackCapabilitySet(null, Set.of("h264"), Set.of("aac"), null, true, true, true, true, true),
                    playbackConstraints(null, audioConstraint(null, 2, null, null, null))
                ),
                null,
                null,
                null,
                null
            )
        );

        assertEquals(PlaybackMode.AUDIO_TRANSCODE, decision.mode());
        assertTrue(decision.reasons().contains(PlaybackReason.AUDIO_CHANNELS_TOO_HIGH));
        assertTrue(decision.videoPreserved());
    }

    @Test
    void sessionServiceUsesDirectPathForDirectPlayDecisions() {
        PlaybackDecision decision = playbackDecision(
            PlaybackMode.DIRECT_PLAY,
            streamDescriptor(StreamingProtocol.FILE, "mp4", false),
            null,
            true,
            true,
            null,
            null
        );
        FixedDecisionService decisionService = new FixedDecisionService(decision);
        FakeTranscodeApplicationService transcodeService = new FakeTranscodeApplicationService();
        LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(decisionService, transcodeService);

        var session = sessionService.openSession(
            playbackRequest(
                mediaSourceRef("/media/test.mp4", null, "object-video-runtime-1", MediaKind.VIDEO),
                0L,
                null,
                null,
                null,
                null,
                null,
                null
            ),
            "alice"
        );

        assertEquals(PlaybackSessionState.READY, session.state());
        assertEquals("object-video-runtime-1", session.objectId());
        assertEquals(MediaKind.VIDEO, session.mediaKind());
        assertEquals(PlaybackMode.DIRECT_PLAY, session.decision().mode());
        assertEquals(PlaybackLifecyclePhase.READY, session.lifecycle().phase());
        assertEquals(100.0, session.lifecycle().progressPercent());
        assertEquals("object-video-runtime-1", session.telemetry().objectId());
        assertEquals(MediaKind.VIDEO, session.telemetry().mediaKind());
        assertTrue(session.lifecycle().canStop());
        assertEquals(0, transcodeService.playbackSubmissions.size());
        assertEquals(Path.of("/media/test.mp4"), sessionService.getDirectContentPath(session.sessionId(), "alice"));
    }

    @Test
    void sessionServiceMapsRemuxDecisionsOntoCurrentTranscodeRuntime() {
        PlaybackDecision decision = playbackDecision(
            PlaybackMode.REMUX,
            streamDescriptor(StreamingProtocol.HLS, "fmp4", true),
            Set.of(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED),
            true,
            true,
            null,
            null
        );
        FixedDecisionService decisionService = new FixedDecisionService(decision);
        FakeTranscodeApplicationService transcodeService = new FakeTranscodeApplicationService(JobStatus.COMPLETED, "#EXTM3U", null, "", null);
        LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(decisionService, transcodeService);

        var session = sessionService.openSession(playbackRequest(mediaSourceRef("/media/test.mp4")), "alice");

        assertEquals(1, transcodeService.playbackSubmissions.size());
        assertEquals(PlaybackMode.REMUX, transcodeService.submittedPlaybackDecisions.get("job-1").mode());
        assertEquals(PlaybackSessionState.READY, session.state());
        assertEquals(PlaybackMode.REMUX, session.decision().mode());
        assertEquals(PlaybackLifecyclePhase.READY, session.lifecycle().phase());
        assertEquals(100.0, session.lifecycle().progressPercent());
        assertTrue(session.decision().reasons().contains(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED));
        assertNull(sessionService.getDirectContentPath(session.sessionId(), "alice"));
        assertEquals("#EXTM3U", sessionService.getHlsManifest(session.sessionId(), "alice"));
    }

    @Test
    void sessionServiceDoesNotTreatFileProtocolTranscodeDecisionAsDirectPlay() {
        PlaybackDecision decision = playbackDecision(
            PlaybackMode.VIDEO_TRANSCODE,
            streamDescriptor(StreamingProtocol.FILE, "mp4", false),
            Set.of(PlaybackReason.VIDEO_CODEC_UNSUPPORTED),
            false,
            false,
            null,
            null
        );
        FixedDecisionService decisionService = new FixedDecisionService(decision);
        FakeTranscodeApplicationService transcodeService = new FakeTranscodeApplicationService();
        LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(decisionService, transcodeService);

        var session = sessionService.openSession(playbackRequest(mediaSourceRef("/media/test-hevc.mkv")), "alice");

        assertEquals(1, transcodeService.playbackSubmissions.size());
        assertEquals(PlaybackMode.VIDEO_TRANSCODE, transcodeService.submittedPlaybackDecisions.get("job-1").mode());
        assertNull(sessionService.getDirectContentPath(session.sessionId(), "alice"));
    }

    @Test
    void sessionServiceMapsAudioTranscodeDecisionsOntoCurrentTranscodeRuntime() {
        PlaybackDecision decision = playbackDecision(
            PlaybackMode.AUDIO_TRANSCODE,
            streamDescriptor(StreamingProtocol.HLS, "fmp4", true),
            Set.of(PlaybackReason.AUDIO_CODEC_UNSUPPORTED),
            true,
            false,
            null,
            null
        );
        FixedDecisionService decisionService = new FixedDecisionService(decision);
        FakeTranscodeApplicationService transcodeService = new FakeTranscodeApplicationService(JobStatus.COMPLETED, "#EXTM3U", null, "", null);
        LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(decisionService, transcodeService);

        var session = sessionService.openSession(playbackRequest(mediaSourceRef("/media/test.mkv")), "alice");

        assertEquals(1, transcodeService.playbackSubmissions.size());
        assertEquals(PlaybackMode.AUDIO_TRANSCODE, transcodeService.submittedPlaybackDecisions.get("job-1").mode());
        assertEquals(PlaybackSessionState.READY, session.state());
        assertEquals(PlaybackMode.AUDIO_TRANSCODE, session.decision().mode());
        assertEquals(PlaybackLifecyclePhase.READY, session.lifecycle().phase());
        assertTrue(session.decision().reasons().contains(PlaybackReason.AUDIO_CODEC_UNSUPPORTED));
        assertEquals("#EXTM3U", sessionService.getHlsManifest(session.sessionId(), "alice"));
    }

    @Test
    void sessionServiceMapsVideoTranscodeDecisionsOntoCurrentTranscodeRuntime() {
        PlaybackDecision decision = playbackDecision(
            PlaybackMode.VIDEO_TRANSCODE,
            streamDescriptor(StreamingProtocol.DASH, "fmp4", true),
            Set.of(PlaybackReason.VIDEO_CODEC_UNSUPPORTED),
            false,
            false,
            null,
            null
        );
        FixedDecisionService decisionService = new FixedDecisionService(decision);
        FakeTranscodeApplicationService transcodeService = new FakeTranscodeApplicationService(JobStatus.COMPLETED, null, "<MPD/>", "", null);
        LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(decisionService, transcodeService);

        var session = sessionService.openSession(playbackRequest(mediaSourceRef("/media/test-hevc.mkv")), "alice");

        assertEquals(1, transcodeService.playbackSubmissions.size());
        assertEquals(PlaybackMode.VIDEO_TRANSCODE, transcodeService.submittedPlaybackDecisions.get("job-1").mode());
        assertEquals(PlaybackSessionState.READY, session.state());
        assertEquals(PlaybackMode.VIDEO_TRANSCODE, session.decision().mode());
        assertEquals(PlaybackLifecyclePhase.READY, session.lifecycle().phase());
        assertTrue(session.decision().reasons().contains(PlaybackReason.VIDEO_CODEC_UNSUPPORTED));
        assertEquals("<MPD/>", sessionService.getDashManifest(session.sessionId(), "alice"));
    }

    @Test
    void sessionServiceExposesStartingLifecycleProgressWhileBackingJobIsPreparing() {
        PlaybackDecision decision = playbackDecision(
            PlaybackMode.REMUX,
            streamDescriptor(StreamingProtocol.HLS, "fmp4", true),
            Set.of(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED),
            true,
            true,
            null,
            null
        );
        FixedDecisionService decisionService = new FixedDecisionService(decision);
        TestJobEventPublisher eventFlow = new TestJobEventPublisher();
        FakeTranscodeApplicationService transcodeService = new FakeTranscodeApplicationService(
            JobStatus.TRANSCODING,
            null,
            null,
            "",
            eventFlow
        );
        LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(decisionService, transcodeService);

        var session = sessionService.openSession(playbackRequest(mediaSourceRef("/media/test.mp4")), "alice");
        assertEquals(PlaybackSessionState.PENDING, session.state());
        assertEquals(PlaybackLifecyclePhase.STARTING, session.lifecycle().phase());
        assertEquals(0.0, session.lifecycle().progressPercent());
        assertTrue(session.lifecycle().canStop());

        eventFlow.emit(new JobEvent.Progress("job-1", 37.5, 1.4, 24.0));

        var updated = sessionService.getSession(session.sessionId(), "alice");
        assertEquals(PlaybackLifecyclePhase.STARTING, updated.lifecycle().phase());
        assertEquals(37.5, updated.lifecycle().progressPercent());
    }

    @Test
    void sessionServiceKeepsStoppedLifecycleVisibleAfterExplicitClose() throws Exception {
        PlaybackDecision decision = playbackDecision(
            PlaybackMode.DIRECT_PLAY,
            streamDescriptor(StreamingProtocol.FILE, "mp4", false),
            null,
            true,
            true,
            null,
            null
        );
        FixedDecisionService decisionService = new FixedDecisionService(decision);
        FakeTranscodeApplicationService transcodeService = new FakeTranscodeApplicationService();
        LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(decisionService, transcodeService);

        var session = sessionService.openSession(playbackRequest(mediaSourceRef("/media/test.mp4")), "alice");
        sessionService.closeSession(session.sessionId(), "alice");

        var stopped = sessionService.getSession(session.sessionId(), "alice");
        assertNotNull(stopped);
        assertEquals(PlaybackSessionState.CLOSED, stopped.state());
        assertEquals(PlaybackLifecyclePhase.STOPPED, stopped.lifecycle().phase());
        assertEquals(PlaybackLifecycleEndReason.CLIENT_REQUESTED, stopped.lifecycle().endReason());
        assertFalse(stopped.lifecycle().canStop());
        assertNull(stopped.artifacts());
        assertNull(sessionService.getDirectContentPath(session.sessionId(), "alice"));
    }

    @Test
    void sessionServiceRecordsSharedPlaybackTelemetryAndCompletionThroughReportEvents() throws Exception {
        PlaybackDecision decision = playbackDecision(
            PlaybackMode.DIRECT_PLAY,
            streamDescriptor(StreamingProtocol.FILE, "mp4", false),
            null,
            true,
            true,
            null,
            null
        );
        FixedDecisionService decisionService = new FixedDecisionService(decision);
        FakeTranscodeApplicationService transcodeService = new FakeTranscodeApplicationService();
        LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(decisionService, transcodeService);

        var session = sessionService.openSession(
            playbackRequest(
                mediaSourceRef(
                    "/media/reportable.mp4",
                    playbackSourceCharacteristics("mp4", 200_000L, null, null, null, null),
                    "object-video-runtime-2",
                    MediaKind.VIDEO
                ),
                0L,
                null,
                null,
                null,
                null,
                null,
                null
            ),
            "alice"
        );

        var started = sessionService.reportPlayback(
            session.sessionId(),
            mediaSessionPlaybackReport(MediaSessionPlaybackEvent.STARTED, null, null, 0L, null, null, "TV App", "Living Room", null),
            "alice"
        );
        var completed = sessionService.reportPlayback(
            session.sessionId(),
            mediaSessionPlaybackReport(MediaSessionPlaybackEvent.COMPLETED, null, null, null, 200_000L, null, null, null, null),
            "alice"
        );

        assertEquals(MediaSessionPlaybackEvent.STARTED, started.telemetry().lastEvent());
        assertEquals("TV App", started.telemetry().clientName());
        assertEquals("Living Room", started.telemetry().deviceName());
        assertEquals("object-video-runtime-2", started.objectId());
        assertEquals(MediaKind.VIDEO, started.mediaKind());
        assertEquals(PlaybackSessionState.CLOSED, completed.state());
        assertEquals(MediaSessionPlaybackEvent.COMPLETED, completed.telemetry().lastEvent());
        assertEquals(100.0, completed.telemetry().progressPercent());
        assertEquals(PlaybackLifecycleEndReason.PLAYBACK_COMPLETED, completed.lifecycle().endReason());
    }

    @Test
    void sessionServiceMapsCancelledBackingJobsOntoAbandonedLifecycle() {
        PlaybackDecision decision = playbackDecision(
            PlaybackMode.REMUX,
            streamDescriptor(StreamingProtocol.HLS, "fmp4", true),
            Set.of(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED),
            true,
            true,
            null,
            null
        );
        FixedDecisionService decisionService = new FixedDecisionService(decision);
        FakeTranscodeApplicationService transcodeService = new FakeTranscodeApplicationService(JobStatus.CANCELLED, null, null, "", null);
        LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(decisionService, transcodeService);

        var session = sessionService.openSession(playbackRequest(mediaSourceRef("/media/test.mp4")), "alice");

        assertEquals(PlaybackSessionState.FAILED, session.state());
        assertEquals(PlaybackLifecyclePhase.ABANDONED, session.lifecycle().phase());
        assertEquals(PlaybackLifecycleEndReason.BACKING_JOB_CANCELLED, session.lifecycle().endReason());
        assertFalse(session.lifecycle().canStop());
    }

    @Test
    void sessionStopCancelsInFlightBackingJobAndEvictsTerminalSessionAfterRetention() throws Exception {
        PlaybackDecision decision = playbackDecision(
            PlaybackMode.REMUX,
            streamDescriptor(StreamingProtocol.HLS, "fmp4", true),
            Set.of(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED),
            true,
            true,
            null,
            null
        );
        FixedDecisionService decisionService = new FixedDecisionService(decision);
        FakeTranscodeApplicationService transcodeService = new FakeTranscodeApplicationService(JobStatus.TRANSCODING, null, null, "", null);
        LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(decisionService, transcodeService, 25L);

        var session = sessionService.openSession(playbackRequest(mediaSourceRef("/media/test.mp4")), "alice");
        sessionService.closeSession(session.sessionId(), "alice");

        assertEquals(List.of("job-1"), transcodeService.cancelledJobs);
        var stopped = sessionService.getSession(session.sessionId(), "alice");
        assertNotNull(stopped);
        assertEquals(PlaybackLifecyclePhase.STOPPED, stopped.lifecycle().phase());

        Thread.sleep(100);

        assertNull(sessionService.getSession(session.sessionId(), "alice"));
    }

    private static ProbeResult videoProbe() {
        return videoProbe("h264", "aac", 2, "webvtt", "mp4");
    }

    private static ProbeResult videoProbe(
        String videoCodec,
        String audioCodec,
        int audioChannels,
        String subtitleCodec,
        String format
    ) {
        return new ProbeResult(
            "/media/test.mp4",
            format,
            120.0,
            1_000_000L,
            new ProbeStreams(
                List.of(new VideoStream(0, videoCodec, 1920, 1080, 24.0, 4_000)),
                List.of(new AudioStream(1, audioCodec, audioChannels, 192)),
                List.of(new SubtitleStream(3, subtitleCodec, "eng", null))
            )
        );
    }

    private static final class FakeMediaProber implements MediaProber {
        private final ProbeResult probeResult;

        private FakeMediaProber(ProbeResult probeResult) {
            this.probeResult = probeResult;
        }

        @Override
        public ProbeResult probe(Path path) {
            return probeResult;
        }

        @Override
        public ProbeResult probeCached(Path path) {
            return probeResult;
        }

        @Override
        public void clearCache() {
        }
    }

    private static final class FixedDecisionService implements PlaybackDecisionService {
        private final PlaybackDecision decision;
        private final List<PlaybackRequest> requests = new ArrayList<>();

        private FixedDecisionService(PlaybackDecision decision) {
            this.decision = decision;
        }

        @Override
        public PlaybackDecision decide(PlaybackRequest request) {
            requests.add(request);
            return decision;
        }
    }

    private static final class FakeTranscodeApplicationService implements TranscodeApplicationService {
        private final JobStatus jobStatus;
        private final String manifestM3u8;
        private final String manifestMpd;
        private final String logs;
        private final TestJobEventPublisher eventFlow;
        private Consumer<JobEvent> onJobEvent;
        private final List<PlaybackRequest> playbackSubmissions = new ArrayList<>();
        private final LinkedHashMap<String, PlaybackDecision> submittedPlaybackDecisions = new LinkedHashMap<>();
        private final List<String> cancelledJobs = new ArrayList<>();
        private final LinkedHashMap<String, TranscodeJob> jobs = new LinkedHashMap<>();

        private FakeTranscodeApplicationService() {
            this(JobStatus.COMPLETED, null, null, "", null);
        }

        private FakeTranscodeApplicationService(
            JobStatus jobStatus,
            String manifestM3u8,
            String manifestMpd,
            String logs,
            TestJobEventPublisher eventFlow
        ) {
            this.jobStatus = jobStatus == null ? JobStatus.COMPLETED : jobStatus;
            this.manifestM3u8 = manifestM3u8;
            this.manifestMpd = manifestMpd;
            this.logs = logs == null ? "" : logs;
            this.eventFlow = eventFlow;
        }

        @Override
        public boolean getCircuitBreakerOpen() {
            return false;
        }

        @Override
        public Consumer<JobEvent> getOnJobEvent() {
            return onJobEvent;
        }

        @Override
        public void setOnJobEvent(Consumer<? super JobEvent> onJobEvent) {
            this.onJobEvent = onJobEvent == null ? null : onJobEvent::accept;
        }

        @Override
        public Flow.Publisher<JobEvent> eventFlow(String jobId) {
            return eventFlow;
        }

        @Override
        public TranscodeJob submit(PlaybackRequest request, PlaybackDecision decision, String batchId, String owner) {
            playbackSubmissions.add(request);
            String jobId = "job-" + playbackSubmissions.size();
            TranscodeJob job = new TranscodeJob(
                jobId,
                jobStatus,
                request.source().path(),
                request.transcode().profileHint() == null ? "h264_fast" : request.transcode().profileHint(),
                StreamRepresentation.HLS_FMP4
            );
            jobs.put(jobId, job);
            submittedPlaybackDecisions.put(job.id(), decision);
            return job;
        }

        @Override
        public TranscodeJob submit(TranscodeRequest request, String batchId, String owner) {
            throw new UnsupportedOperationException("Not used in playback runtime tests");
        }

        @Override
        public BatchSubmitResponse submitBatch(List<TranscodeRequest> requests, String owner) {
            throw new UnsupportedOperationException("Not used in playback runtime tests");
        }

        @Override
        public void cancel(String jobId, String owner) {
            cancelledJobs.add(jobId);
        }

        @Override
        public BatchCancelResponse cancelBatch(String batchId, String owner) {
            return null;
        }

        @Override
        public BatchStatusResponse getBatchStatus(String batchId, String owner) {
            return null;
        }

        @Override
        public TranscodeJob getJob(String jobId) {
            return jobs.get(jobId);
        }

        @Override
        public TranscodeJobListing listJobs(int page, int limit, String owner) {
            return new TranscodeJobListing(new ArrayList<>(jobs.values()), jobs.size(), page, limit);
        }

        @Override
        public TranscodeJobListing listJobsFiltered(JobStatus status, Integer sinceMinutes, int page, int limit, String owner) {
            return new TranscodeJobListing(new ArrayList<>(jobs.values()), jobs.size(), page, limit);
        }

        @Override
        public String getLogs(String jobId) {
            return logs;
        }

        @Override
        public String getManifestMpd(String jobId) {
            return manifestMpd;
        }

        @Override
        public String getManifestM3u8(String jobId) {
            return manifestM3u8;
        }

        @Override
        public String getSubtitlePlaylist(String jobId, int trackIndex) {
            return null;
        }

        @Override
        public String getHlsMediaPlaylist(String jobId, String representationId) {
            return null;
        }

        @Override
        public Path getSegmentOutputDir(String jobId) {
            return null;
        }
    }

    private static final class TestJobEventPublisher implements Flow.Publisher<JobEvent> {
        private final List<TestSubscription> subscriptions = new ArrayList<>();

        @Override
        public void subscribe(Flow.Subscriber<? super JobEvent> subscriber) {
            TestSubscription subscription = new TestSubscription(subscriber);
            subscriptions.add(subscription);
            subscriber.onSubscribe(subscription);
        }

        private void emit(JobEvent event) {
            for (TestSubscription subscription : List.copyOf(subscriptions)) {
                if (!subscription.cancelled) {
                    subscription.subscriber.onNext(event);
                }
            }
        }

        private static final class TestSubscription implements Flow.Subscription {
            private final Flow.Subscriber<? super JobEvent> subscriber;
            private volatile boolean cancelled;

            private TestSubscription(Flow.Subscriber<? super JobEvent> subscriber) {
                this.subscriber = subscriber;
            }

            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        }
    }

}
