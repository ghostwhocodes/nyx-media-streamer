package com.nyx.playback.contracts;

import com.nyx.json.NyxJson;
import com.nyx.media.contracts.MediaKind;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackContractsTest {
    @Test
    void playbackRequestDefaultsToAdaptiveHlsDashOutput() {
        PlaybackRequest request = new PlaybackRequest(new MediaSourceRef("/media/example.mkv"));

        assertThat(request.output().allowedProtocols()).containsExactly(StreamingProtocol.HLS, StreamingProtocol.DASH);
        assertThat(request.output().allowAdaptiveStreaming()).isTrue();
        assertThat(request.output().preferredRepresentation()).isNull();
        assertThat(request.selection().audio().mode()).isEqualTo(AudioTrackSelectionMode.ALL);
        assertThat(request.selection().subtitles().mode()).isEqualTo(SubtitleSelectionMode.EXTRACT);
    }

    @Test
    void streamPackagingStrategyModelsMpegTsHlsWithoutClientSpecificNames() {
        PlaybackOutputPreferences output = new PlaybackOutputPreferences(
            Set.of(StreamingProtocol.HLS),
            StreamingProtocol.HLS,
            true,
            StreamRepresentation.HLS_MPEG_TS
        );
        StreamDescriptor descriptor = new StreamDescriptor(
            StreamingProtocol.HLS,
            null,
            true,
            output.preferredRepresentation()
        );

        assertThat(descriptor.protocol()).isEqualTo(StreamingProtocol.HLS);
        assertThat(descriptor.container()).isEqualTo("mpegts");
        assertThat(descriptor.representation()).isEqualTo(StreamRepresentation.HLS_MPEG_TS);
    }

    @Test
    void contractsSerializeWithStableEnumNames() throws Exception {
        PlaybackRequest request = new PlaybackRequest(
            new MediaSourceRef(
                "/media/example.mkv",
                new PlaybackSourceCharacteristics(
                    "mkv",
                    7_200_000L,
                    9_000_000_000L,
                    List.of(new PlaybackSourceVideoStream(0, "h264", 1920, 1080, 23.976, 8_000)),
                    List.of(new PlaybackSourceAudioStream(1, "aac", 6, 640, null, "eng", null)),
                    List.of(new PlaybackSourceSubtitleStream(2, "webvtt", "eng", null))
                ),
                null,
                null
            ),
            0L,
            new PlaybackOutputPreferences(),
            new PlaybackClientProfile(
                "mobile_hls",
                new PlaybackCapabilitySet(
                    new LinkedHashSet<>(List.of("mp4")),
                    new LinkedHashSet<>(List.of("h264")),
                    new LinkedHashSet<>(List.of("aac")),
                    new LinkedHashSet<>(List.of("webvtt")),
                    true,
                    true,
                    true,
                    true,
                    true
                ),
                new PlaybackConstraints(
                    new VideoConstraint(1280, 720, 2500),
                    new AudioConstraint(192, 2, null, null, null)
                )
            ),
            new PlaybackCapabilitySet(
                new LinkedHashSet<>(List.of("mkv", "mp4")),
                new LinkedHashSet<>(List.of("h264")),
                new LinkedHashSet<>(List.of("aac")),
                new LinkedHashSet<>(List.of("webvtt")),
                false,
                true,
                true,
                true,
                true
            ),
            new PlaybackSelection(
                new AudioTrackSelection(AudioTrackSelectionMode.SPECIFIC, List.of(1)),
                new SubtitleSelection(SubtitleSelectionMode.EXTRACT, 2)
            ),
            new PlaybackConstraints(
                new VideoConstraint(1280, 720, 4000),
                new AudioConstraint(256, 2, null, null, null)
            ),
            new TranscodePreferences(
                "adaptive_h264",
                HardwareAccelerationPreference.AUTO,
                List.of(new RepresentationConstraint(1280, 720, 3000))
            )
        );

        String encoded = NyxJson.newMapper().writeValueAsString(request);

        assertThat(encoded).contains("\"profileHint\":\"adaptive_h264\"");
        assertThat(encoded).contains("\"id\":\"mobile_hls\"");
        assertThat(encoded).contains("\"allowedProtocols\":[\"HLS\",\"DASH\"]");
        assertThat(encoded).contains("\"container\":\"mkv\"");
        assertThat(encoded).contains("\"supportedContainers\":[\"mkv\",\"mp4\"]");
        assertThat(encoded).contains("\"maxWidth\":1280");
        assertThat(encoded).contains("\"trackIndex\":2");
    }

    @Test
    void playbackDecisionSerializesNewPhase1Fields() throws Exception {
        PlaybackDecision decision = new PlaybackDecision(
            PlaybackMode.REMUX,
            new StreamDescriptor(StreamingProtocol.HLS, "fmp4", true),
            Set.of(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED),
            true,
            true,
            SubtitleDelivery.SIDECAR,
            new PlaybackOutputSummary("h264", Set.of("aac"))
        );

        String encoded = NyxJson.newMapper().writeValueAsString(decision);

        assertThat(encoded).contains("\"mode\":\"REMUX\"");
        assertThat(encoded).contains("\"subtitleDelivery\":\"SIDECAR\"");
        assertThat(encoded).contains("\"videoPreserved\":true");
        assertThat(encoded).contains("\"audioCodecs\":[\"aac\"]");
    }

    @Test
    void playbackSessionSerializesNegotiatedDecisionDetails() throws Exception {
        PlaybackSession session = new PlaybackSession(
            "session123",
            "object-video-1",
            MediaKind.VIDEO,
            PlaybackSessionState.READY,
            new PlaybackDecision(
                PlaybackMode.AUDIO_TRANSCODE,
                new StreamDescriptor(StreamingProtocol.HLS, "fmp4", true),
                Set.of(PlaybackReason.AUDIO_CODEC_UNSUPPORTED),
                true,
                false,
                SubtitleDelivery.NONE,
                new PlaybackOutputSummary()
            ),
            new PlaybackSessionArtifacts(
                StreamingProtocol.HLS,
                "/api/v1/playback/sessions/session123/master.m3u8",
                null,
                "/api/v1/playback/sessions/session123/master.m3u8",
                null
            ),
            new MediaSessionTelemetry(
                "object-video-1",
                MediaKind.VIDEO,
                MediaSessionPlaybackEvent.HEARTBEAT,
                "2026-04-09T12:03:00Z",
                30_000L,
                120_000L,
                25.0,
                "Web Player",
                null,
                null
            ),
            null,
            null,
            "2026-04-09T12:00:00Z",
            new PlaybackSessionLifecycle(
                PlaybackLifecyclePhase.READY,
                "2026-04-09T12:00:00Z",
                "2026-04-09T12:00:02Z",
                null,
                100.0,
                true,
                null
            )
        );

        String encoded = NyxJson.newMapper().writeValueAsString(session);

        assertThat(encoded).contains("\"state\":\"READY\"");
        assertThat(encoded).contains("\"objectId\":\"object-video-1\"");
        assertThat(encoded).contains("\"mediaKind\":\"VIDEO\"");
        assertThat(encoded).contains("\"mode\":\"AUDIO_TRANSCODE\"");
        assertThat(encoded).contains("\"reasons\":[\"AUDIO_CODEC_UNSUPPORTED\"]");
        assertThat(encoded).contains("\"lastEvent\":\"HEARTBEAT\"");
        assertThat(encoded).contains("\"phase\":\"READY\"");
        assertThat(encoded).contains("\"progressPercent\":100.0");
    }

    @Test
    void playbackDeliveryContractsModelGenericStartupReadinessLeaseAndOutcomes() throws Exception {
        PlaybackDeliveryReadiness readiness = PlaybackDeliveryReadiness.hlsManifestWithBackingJob();
        PlaybackDeliveryStartupPolicy startupPolicy = new PlaybackDeliveryStartupPolicy(
            3,
            25L,
            PlaybackDeliveryTimeoutAction.FAIL,
            new PlaybackDeliveryRetry(4, "waiting")
        );
        PlaybackDeliveryLeasePolicy leasePolicy = new PlaybackDeliveryLeasePolicy(
            "alice:/media/example.mkv:medium",
            60_000L,
            true,
            true,
            true,
            true,
            true
        );
        PlaybackDeliveryRequest deliveryRequest = new PlaybackDeliveryRequest(
            new PlaybackRequest(new MediaSourceRef("/media/example.mkv")),
            "alice",
            readiness,
            startupPolicy,
            leasePolicy
        );
        PlaybackSession session = new PlaybackSession(
            "session-delivery",
            "object-video-1",
            MediaKind.VIDEO,
            PlaybackSessionState.READY,
            null,
            new PlaybackSessionArtifacts(StreamingProtocol.HLS, "/api/v1/playback/sessions/session-delivery/master.m3u8"),
            null,
            null,
            null,
            "2026-06-13T12:00:00Z",
            new PlaybackSessionLifecycle(
                PlaybackLifecyclePhase.READY,
                "2026-06-13T12:00:00Z",
                "2026-06-13T12:00:02Z",
                null,
                100.0,
                true,
                null
            )
        );

        PlaybackDeliveryOutcome manifest = new PlaybackDeliveryReadyManifest(
            session,
            StreamingProtocol.HLS,
            "#EXTM3U\n",
            "job-1"
        );
        PlaybackDeliveryOutcome file = new PlaybackDeliveryReadyFile(session, Path.of("/media/example.mp4"));
        PlaybackDeliveryOutcome pending = new PlaybackDeliveryPending(session, null);
        PlaybackDeliveryOutcome unavailable = new PlaybackDeliveryUnavailable(
            session,
            PlaybackDeliveryRequirement.HLS_MANIFEST,
            "HLS manifest not found"
        );
        PlaybackDeliveryOutcome failed = new PlaybackDeliveryFailed(session, "TRANSCODE_FAILED", "transcode failed");
        PlaybackDeliveryOutcome terminated = new PlaybackDeliveryTerminated(
            session,
            PlaybackLifecyclePhase.STOPPED,
            "Playback session stopped: session-delivery"
        );

        assertThat(deliveryRequest.readiness().requirements())
            .containsExactlyInAnyOrder(PlaybackDeliveryRequirement.HLS_MANIFEST, PlaybackDeliveryRequirement.BACKING_JOB);
        assertThat(deliveryRequest.startupPolicy().timeoutAction()).isEqualTo(PlaybackDeliveryTimeoutAction.FAIL);
        assertThat(deliveryRequest.leasePolicy().enabled()).isTrue();
        assertThat(manifest.sessionId()).isEqualTo("session-delivery");
        assertThat(((PlaybackDeliveryReadyManifest) manifest).backingJobId()).isEqualTo("job-1");
        assertThat(((PlaybackDeliveryReadyFile) file).path()).isEqualTo(Path.of("/media/example.mp4"));
        assertThat(((PlaybackDeliveryPending) pending).retry().retryAfterSeconds()).isEqualTo(2);
        assertThat(((PlaybackDeliveryUnavailable) unavailable).requirement()).isEqualTo(PlaybackDeliveryRequirement.HLS_MANIFEST);
        assertThat(((PlaybackDeliveryFailed) failed).message()).isEqualTo("transcode failed");
        assertThat(((PlaybackDeliveryTerminated) terminated).phase()).isEqualTo(PlaybackLifecyclePhase.STOPPED);

        String encoded = NyxJson.newMapper().writeValueAsString(deliveryRequest);

        assertThat(encoded).contains("\"requirements\":[");
        assertThat(encoded).contains("\"HLS_MANIFEST\"");
        assertThat(encoded).contains("\"BACKING_JOB\"");
        assertThat(encoded).contains("\"timeoutAction\":\"FAIL\"");
        assertThat(encoded.toLowerCase()).doesNotContain("qloud");
    }
}
