package com.nyx.playback;

import com.nyx.media.contracts.MediaKind;
import com.nyx.playback.contracts.AudioNegotiationDecision;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.AudioSession;
import com.nyx.playback.contracts.AudioSessionArtifacts;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.nyx.playback.contracts.MediaSessionTelemetry;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackLifecycleEndReason;
import com.nyx.playback.contracts.PlaybackLifecyclePhase;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackOutputSummary;
import com.nyx.playback.contracts.PlaybackReason;
import com.nyx.playback.contracts.PlaybackSession;
import com.nyx.playback.contracts.PlaybackSessionArtifacts;
import com.nyx.playback.contracts.PlaybackSessionLifecycle;
import com.nyx.playback.contracts.PlaybackSessionState;
import com.nyx.playback.contracts.PlaybackSourceAudioStream;
import com.nyx.playback.contracts.PlaybackSourceCharacteristics;
import com.nyx.playback.contracts.PlaybackSourceSubtitleStream;
import com.nyx.playback.contracts.PlaybackSourceVideoStream;
import com.nyx.playback.contracts.StreamDescriptor;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.SubtitleDelivery;
import java.util.List;
import java.util.Set;

public final class PlaybackContractFactories {
    private PlaybackContractFactories() {
    }

    public static StreamDescriptor streamDescriptor(StreamingProtocol protocol, String container, boolean adaptive) {
        return new StreamDescriptor(protocol, container, adaptive);
    }

    public static PlaybackDecision playbackDecision(
        PlaybackMode mode,
        StreamDescriptor stream,
        Set<PlaybackReason> reasons,
        boolean videoPreserved,
        boolean audioPreserved,
        SubtitleDelivery subtitleDelivery,
        PlaybackOutputSummary output
    ) {
        return new PlaybackDecision(mode, stream, reasons, videoPreserved, audioPreserved, subtitleDelivery, output);
    }

    public static PlaybackDecision playbackDecision(
        PlaybackMode mode,
        StreamDescriptor stream,
        Set<PlaybackReason> reasons,
        boolean videoPreserved,
        boolean audioPreserved
    ) {
        return playbackDecision(mode, stream, reasons, videoPreserved, audioPreserved, SubtitleDelivery.NONE, new PlaybackOutputSummary());
    }

    public static PlaybackSourceCharacteristics playbackSourceCharacteristics(
        String container,
        Long durationMillis,
        Long sizeBytes,
        List<PlaybackSourceVideoStream> videoStreams,
        List<PlaybackSourceAudioStream> audioStreams,
        List<PlaybackSourceSubtitleStream> subtitleStreams
    ) {
        return new PlaybackSourceCharacteristics(
            container,
            durationMillis,
            sizeBytes,
            videoStreams,
            audioStreams,
            subtitleStreams
        );
    }

    public static PlaybackSourceAudioStream playbackSourceAudioStream(
        int index,
        String codec,
        int channels,
        Integer bitrateKbps,
        Integer sampleRateHz,
        String language,
        String title
    ) {
        return new PlaybackSourceAudioStream(index, codec, channels, bitrateKbps, sampleRateHz, language, title);
    }

    public static MediaSessionTelemetry mediaSessionTelemetry(
        String objectId,
        MediaKind mediaKind,
        MediaSessionPlaybackEvent lastEvent,
        String lastEventAt,
        Long positionMillis,
        Long durationMillis,
        Double progressPercent,
        String clientName,
        String deviceName,
        String playbackContext
    ) {
        return new MediaSessionTelemetry(
            objectId,
            mediaKind,
            lastEvent,
            lastEventAt,
            positionMillis,
            durationMillis,
            progressPercent,
            clientName,
            deviceName,
            playbackContext
        );
    }

    public static PlaybackSessionArtifacts playbackSessionArtifacts(
        StreamingProtocol protocol,
        String playbackUrl,
        String directContentUrl,
        String hlsMasterUrl,
        String dashManifestUrl
    ) {
        return new PlaybackSessionArtifacts(protocol, playbackUrl, directContentUrl, hlsMasterUrl, dashManifestUrl);
    }

    public static PlaybackSessionLifecycle playbackSessionLifecycle(
        PlaybackLifecyclePhase phase,
        String startedAt,
        String readyAt,
        String endedAt,
        Double progressPercent,
        boolean canStop,
        PlaybackLifecycleEndReason endReason
    ) {
        return new PlaybackSessionLifecycle(phase, startedAt, readyAt, endedAt, progressPercent, canStop, endReason);
    }

    public static PlaybackSession playbackSession(
        String sessionId,
        String objectId,
        MediaKind mediaKind,
        PlaybackSessionState state,
        PlaybackDecision decision,
        PlaybackSessionArtifacts artifacts,
        MediaSessionTelemetry telemetry,
        String failureCode,
        String failureMessage,
        String createdAt,
        PlaybackSessionLifecycle lifecycle
    ) {
        return new PlaybackSession(
            sessionId,
            objectId,
            mediaKind,
            state,
            decision,
            artifacts,
            telemetry,
            failureCode,
            failureMessage,
            createdAt,
            lifecycle
        );
    }

    public static AudioSession audioSession(
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
        return new AudioSession(
            sessionId,
            objectId,
            mediaKind,
            state,
            request,
            decision,
            artifacts,
            telemetry,
            failureCode,
            failureMessage,
            createdAt,
            lifecycle
        );
    }
}
