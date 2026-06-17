package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackContractDefaultsCoverageTest {
    @Test
    void migratedPlaybackContractsKeepConvenienceConstructorsAndDefaults() {
        PlaybackCapabilitySet capabilities = new PlaybackCapabilitySet();
        PlaybackClientProfile clientProfile = new PlaybackClientProfile("mobile");
        StreamDescriptor stream = new StreamDescriptor(StreamingProtocol.HLS);
        PlaybackDecision decision = new PlaybackDecision(PlaybackMode.REMUX, stream);
        PlaybackOutputPreferences output = new PlaybackOutputPreferences(Set.of(StreamingProtocol.DASH), null);
        PlaybackSourceCharacteristics characteristics = new PlaybackSourceCharacteristics("mp4");
        PlaybackSourceVideoStream videoStream = new PlaybackSourceVideoStream(0, "h264", 1920, 1080, 23.976);
        PlaybackSourceAudioStream audioStream = new PlaybackSourceAudioStream(1, "aac", 2);
        PlaybackSourceSubtitleStream subtitleStream = new PlaybackSourceSubtitleStream(2, "webvtt");
        AudioFormatDescriptor format = new AudioFormatDescriptor();
        AudioClientIdentity clientIdentity = new AudioClientIdentity();
        AudioNegotiationDecision audioDecision = new AudioNegotiationDecision(AudioDeliveryMode.DIRECT_PLAY);
        PlaybackSessionArtifacts artifacts = new PlaybackSessionArtifacts(StreamingProtocol.HLS, "/api/v1/playback/session/master.m3u8");
        AudioTrackSelection audioTrackSelection = new AudioTrackSelection(AudioTrackSelectionMode.SPECIFIC);
        PlaybackDeliveryReadiness deliveryReadiness = new PlaybackDeliveryReadiness();
        PlaybackDeliveryStartupPolicy deliveryStartupPolicy = new PlaybackDeliveryStartupPolicy();
        PlaybackDeliveryRetry deliveryRetry = new PlaybackDeliveryRetry();
        PlaybackDeliveryLeasePolicy deliveryLeasePolicy = new PlaybackDeliveryLeasePolicy();
        PlaybackDeliveryRequest deliveryRequest = new PlaybackDeliveryRequest(
            new PlaybackRequest(new MediaSourceRef("/media/default.mkv")),
            null,
            null
        );
        PlaybackDeliverySessionRequest deliverySessionRequest = new PlaybackDeliverySessionRequest("session-1", null, null);
        PlaybackDeliveryPending pendingDelivery = new PlaybackDeliveryPending(null, null);

        assertTrue(capabilities.allowDirectPlay());
        assertTrue(capabilities.allowRemux());
        assertTrue(capabilities.allowAudioTranscode());
        assertTrue(capabilities.allowVideoTranscode());
        assertTrue(capabilities.allowSubtitleBurnIn());
        assertTrue(clientProfile.capabilities().supportedContainers().isEmpty());
        assertNotNull(clientProfile.constraints());
        assertNull(stream.container());
        assertFalse(stream.adaptive());
        assertTrue(decision.reasons().isEmpty());
        assertEquals(SubtitleDelivery.NONE, decision.subtitleDelivery());
        assertNotNull(decision.output());
        assertEquals(Set.of(StreamingProtocol.DASH), output.allowedProtocols());
        assertTrue(output.allowAdaptiveStreaming());
        assertTrue(characteristics.videoStreams().isEmpty());
        assertTrue(characteristics.audioStreams().isEmpty());
        assertTrue(characteristics.subtitleStreams().isEmpty());
        assertNull(videoStream.bitrateKbps());
        assertNull(audioStream.bitrateKbps());
        assertNull(audioStream.sampleRateHz());
        assertNull(subtitleStream.language());
        assertNull(subtitleStream.title());
        assertNull(format.container());
        assertNull(clientIdentity.clientId());
        assertTrue(audioDecision.reasons().isEmpty());
        assertEquals("/api/v1/playback/session/master.m3u8", artifacts.playbackUrl());
        assertTrue(audioTrackSelection.trackIndices().isEmpty());
        assertTrue(deliveryReadiness.requirements().isEmpty());
        assertNull(deliveryReadiness.manifestProtocol());
        assertEquals(1, deliveryStartupPolicy.pollAttempts());
        assertEquals(0L, deliveryStartupPolicy.pollDelayMillis());
        assertEquals(PlaybackDeliveryTimeoutAction.RETURN_PENDING, deliveryStartupPolicy.timeoutAction());
        assertEquals(2, deliveryRetry.retryAfterSeconds());
        assertEquals("pending", deliveryRetry.status());
        assertFalse(deliveryLeasePolicy.enabled());
        assertNotNull(deliveryRequest.readiness());
        assertNotNull(deliveryRequest.startupPolicy());
        assertNotNull(deliveryRequest.leasePolicy());
        assertNotNull(deliverySessionRequest.readiness());
        assertEquals("session-1", deliverySessionRequest.sessionId());
        assertEquals(2, pendingDelivery.retry().retryAfterSeconds());
    }

    @Test
    void migratedPlaybackContractsDefensivelyCopyMutableCollections() {
        PlaybackCapabilitySet capabilities = new PlaybackCapabilitySet(
            new LinkedHashSet<>(Set.of("mp4")),
            new LinkedHashSet<>(Set.of("h264")),
            new LinkedHashSet<>(Set.of("aac")),
            new LinkedHashSet<>(Set.of("webvtt")),
            true,
            true,
            true,
            true,
            true
        );
        PlaybackDecision decision = new PlaybackDecision(
            PlaybackMode.DIRECT_PLAY,
            new StreamDescriptor(StreamingProtocol.DASH, "mp4", true),
            new LinkedHashSet<>(Set.of(PlaybackReason.AUDIO_CODEC_UNSUPPORTED)),
            true,
            true,
            null,
            null
        );
        PlaybackSourceCharacteristics characteristics = new PlaybackSourceCharacteristics(
            "mkv",
            120_000L,
            8_000_000L,
            new ArrayList<>(List.of(new PlaybackSourceVideoStream(0, "h264", 1920, 1080, 23.976, 8_000))),
            new ArrayList<>(List.of(new PlaybackSourceAudioStream(1, "aac", 2, 192, 48_000, "eng", "Stereo"))),
            new ArrayList<>(List.of(new PlaybackSourceSubtitleStream(2, "webvtt", "eng", "English")))
        );
        AudioTrackSelection trackSelection = new AudioTrackSelection(
            AudioTrackSelectionMode.SPECIFIC,
            new ArrayList<>(List.of(1, 2))
        );

        assertThrows(UnsupportedOperationException.class, () -> capabilities.supportedContainers().add("mkv"));
        assertThrows(UnsupportedOperationException.class, () -> capabilities.supportedVideoCodecs().add("hevc"));
        assertThrows(UnsupportedOperationException.class, () -> decision.reasons().add(PlaybackReason.CONTAINER_UNSUPPORTED));
        assertThrows(UnsupportedOperationException.class, () -> characteristics.videoStreams().add(new PlaybackSourceVideoStream(3, "vp9", 1280, 720, 30.0)));
        assertThrows(UnsupportedOperationException.class, () -> characteristics.audioStreams().add(new PlaybackSourceAudioStream(4, "opus", 2)));
        assertThrows(UnsupportedOperationException.class, () -> characteristics.subtitleStreams().add(new PlaybackSourceSubtitleStream(5, "ass")));
        assertThrows(UnsupportedOperationException.class, () -> trackSelection.trackIndices().add(3));
        assertThrows(UnsupportedOperationException.class, () -> new PlaybackDeliveryReadiness(
            new LinkedHashSet<>(Set.of(PlaybackDeliveryRequirement.HLS_MANIFEST))
        ).requirements().add(PlaybackDeliveryRequirement.BACKING_JOB));
        assertEquals(SubtitleDelivery.NONE, decision.subtitleDelivery());
        assertNotNull(decision.output());
    }

    @Test
    void playbackDeliveryContractsRejectInvalidTimingPolicies() {
        assertThrows(IllegalArgumentException.class, () -> new PlaybackDeliveryStartupPolicy(0, 0L));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackDeliveryStartupPolicy(1, -1L));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackDeliveryRetry(-1, "pending"));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackDeliveryLeasePolicy("lease", -1L));
    }

    @Test
    void reportResultWrappersExposeSessionsWithoutExtraAdapters() {
        PlaybackSession playbackSession = new PlaybackSession(
            "session-1",
            "object-1",
            MediaKind.VIDEO,
            PlaybackSessionState.READY,
            null,
            null,
            null,
            null,
            null,
            "2026-05-01T10:00:00Z",
            null
        );
        AudioSession audioSession = new AudioSession(
            "audio-session-1",
            "object-2",
            MediaKind.AUDIO,
            PlaybackSessionState.READY,
            null,
            null,
            null,
            null,
            null,
            null,
            "2026-05-01T10:00:00Z",
            null
        );

        MediaSessionReportResult.Playback playbackResult = new MediaSessionReportResult.Playback(playbackSession);
        MediaSessionReportResult.Audio audioResult = new MediaSessionReportResult.Audio(audioSession);

        assertEquals("session-1", playbackResult.session().sessionId());
        assertEquals("audio-session-1", audioResult.session().sessionId());
    }
}
