package com.nyx.playback;

import com.nyx.media.contracts.MediaKind;
import com.nyx.playback.contracts.AudioCapabilitySet;
import com.nyx.playback.contracts.AudioClientIdentity;
import com.nyx.playback.contracts.AudioConstraint;
import com.nyx.playback.contracts.AudioDeliveryMode;
import com.nyx.playback.contracts.AudioFormatDescriptor;
import com.nyx.playback.contracts.AudioNegotiationDecision;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.AudioOutputPreferences;
import com.nyx.playback.contracts.AudioSession;
import com.nyx.playback.contracts.AudioSessionArtifacts;
import com.nyx.playback.contracts.AudioTrackSelection;
import com.nyx.playback.contracts.AudioTrackSelectionMode;
import com.nyx.playback.contracts.HardwareAccelerationPreference;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.nyx.playback.contracts.MediaSessionTelemetry;
import com.nyx.playback.contracts.MediaSourceRef;
import com.nyx.playback.contracts.PlaybackCapabilitySet;
import com.nyx.playback.contracts.PlaybackClientProfile;
import com.nyx.playback.contracts.PlaybackConstraints;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackLifecycleEndReason;
import com.nyx.playback.contracts.PlaybackLifecyclePhase;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackOutputPreferences;
import com.nyx.playback.contracts.PlaybackOutputSummary;
import com.nyx.playback.contracts.PlaybackReason;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSelection;
import com.nyx.playback.contracts.PlaybackSession;
import com.nyx.playback.contracts.PlaybackSessionArtifacts;
import com.nyx.playback.contracts.PlaybackSessionLifecycle;
import com.nyx.playback.contracts.PlaybackSessionState;
import com.nyx.playback.contracts.PlaybackSourceAudioStream;
import com.nyx.playback.contracts.PlaybackSourceCharacteristics;
import com.nyx.playback.contracts.PlaybackSourceSubtitleStream;
import com.nyx.playback.contracts.PlaybackSourceVideoStream;
import com.nyx.playback.contracts.RepresentationConstraint;
import com.nyx.playback.contracts.StreamDescriptor;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.SubtitleDelivery;
import com.nyx.playback.contracts.SubtitleSelection;
import com.nyx.playback.contracts.SubtitleSelectionMode;
import com.nyx.playback.contracts.TranscodePreferences;
import com.nyx.playback.contracts.VideoConstraint;

import java.util.List;
import java.util.Set;

final class PlaybackContractFactories {
    private PlaybackContractFactories() {
    }

    static PlaybackDecision playbackDecision(
        PlaybackMode mode,
        StreamDescriptor stream,
        Set<PlaybackReason> reasons,
        boolean videoPreserved,
        boolean audioPreserved,
        SubtitleDelivery subtitleDelivery,
        PlaybackOutputSummary output
    ) {
        return new PlaybackDecision(
            mode,
            stream,
            reasons == null ? Set.of() : reasons,
            videoPreserved,
            audioPreserved,
            subtitleDelivery == null ? SubtitleDelivery.NONE : subtitleDelivery,
            output == null ? new PlaybackOutputSummary() : output
        );
    }

    static StreamDescriptor streamDescriptor(StreamingProtocol protocol, String container, boolean adaptive) {
        return new StreamDescriptor(protocol, container, adaptive);
    }

    static MediaSourceRef mediaSourceRef(String path) {
        return new MediaSourceRef(path);
    }

    static MediaSourceRef mediaSourceRef(
        String path,
        PlaybackSourceCharacteristics characteristics,
        String objectId,
        MediaKind mediaKind
    ) {
        return new MediaSourceRef(path, characteristics, objectId, mediaKind);
    }

    static PlaybackRequest playbackRequest(MediaSourceRef source) {
        return new PlaybackRequest(source);
    }

    static PlaybackRequest playbackRequest(
        MediaSourceRef source,
        long startPositionMillis,
        PlaybackOutputPreferences output,
        PlaybackClientProfile clientProfile,
        PlaybackCapabilitySet capabilities,
        PlaybackSelection selection,
        PlaybackConstraints constraints,
        TranscodePreferences transcode
    ) {
        return new PlaybackRequest(
            source,
            startPositionMillis,
            output == null ? new PlaybackOutputPreferences() : output,
            clientProfile,
            capabilities,
            selection == null ? new PlaybackSelection() : selection,
            constraints == null ? new PlaybackConstraints() : constraints,
            transcode == null ? new TranscodePreferences() : transcode
        );
    }

    static TranscodePreferences transcodePreferences(
        String profileHint,
        HardwareAccelerationPreference hardwareAcceleration,
        List<RepresentationConstraint> explicitRepresentations
    ) {
        return new TranscodePreferences(
            profileHint,
            hardwareAcceleration == null ? HardwareAccelerationPreference.AUTO : hardwareAcceleration,
            explicitRepresentations == null ? List.of() : explicitRepresentations
        );
    }

    static PlaybackOutputPreferences playbackOutputPreferences(
        Set<StreamingProtocol> allowedProtocols,
        StreamingProtocol preferredProtocol,
        boolean allowAdaptiveStreaming
    ) {
        return new PlaybackOutputPreferences(
            allowedProtocols == null ? Set.of(StreamingProtocol.HLS, StreamingProtocol.DASH) : allowedProtocols,
            preferredProtocol,
            allowAdaptiveStreaming
        );
    }

    static PlaybackCapabilitySet playbackCapabilitySet(
        Set<String> supportedContainers,
        Set<String> supportedVideoCodecs,
        Set<String> supportedAudioCodecs,
        Set<String> supportedSubtitleFormats,
        boolean allowDirectPlay,
        boolean allowRemux,
        boolean allowAudioTranscode,
        boolean allowVideoTranscode,
        boolean allowSubtitleBurnIn
    ) {
        return new PlaybackCapabilitySet(
            supportedContainers == null ? Set.of() : supportedContainers,
            supportedVideoCodecs == null ? Set.of() : supportedVideoCodecs,
            supportedAudioCodecs == null ? Set.of() : supportedAudioCodecs,
            supportedSubtitleFormats == null ? Set.of() : supportedSubtitleFormats,
            allowDirectPlay,
            allowRemux,
            allowAudioTranscode,
            allowVideoTranscode,
            allowSubtitleBurnIn
        );
    }

    static PlaybackClientProfile playbackClientProfile(
        String id,
        PlaybackCapabilitySet capabilities,
        PlaybackConstraints constraints
    ) {
        return new PlaybackClientProfile(
            id,
            capabilities == null ? new PlaybackCapabilitySet() : capabilities,
            constraints == null ? new PlaybackConstraints() : constraints
        );
    }

    static PlaybackConstraints playbackConstraints(VideoConstraint video, AudioConstraint audio) {
        return new PlaybackConstraints(
            video == null ? new VideoConstraint() : video,
            audio == null ? new AudioConstraint() : audio
        );
    }

    static PlaybackSelection playbackSelection(AudioTrackSelection audio, SubtitleSelection subtitles) {
        return new PlaybackSelection(
            audio == null ? new AudioTrackSelection() : audio,
            subtitles == null ? new SubtitleSelection() : subtitles
        );
    }

    static AudioTrackSelection audioTrackSelection(AudioTrackSelectionMode mode, List<Integer> trackIndices) {
        return new AudioTrackSelection(
            mode == null ? AudioTrackSelectionMode.ALL : mode,
            trackIndices == null ? List.of() : trackIndices
        );
    }

    static SubtitleSelection subtitleSelection(SubtitleSelectionMode mode, Integer trackIndex) {
        return new SubtitleSelection(mode == null ? SubtitleSelectionMode.EXTRACT : mode, trackIndex);
    }

    static PlaybackSourceCharacteristics playbackSourceCharacteristics(
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
            videoStreams == null ? List.of() : videoStreams,
            audioStreams == null ? List.of() : audioStreams,
            subtitleStreams == null ? List.of() : subtitleStreams
        );
    }

    static PlaybackSourceVideoStream playbackSourceVideoStream(
        int index,
        String codec,
        int width,
        int height,
        double fps,
        Integer bitrateKbps
    ) {
        return new PlaybackSourceVideoStream(index, codec, width, height, fps, bitrateKbps);
    }

    static PlaybackSourceAudioStream playbackSourceAudioStream(
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

    static PlaybackSourceSubtitleStream playbackSourceSubtitleStream(
        int index,
        String codec,
        String language,
        String title
    ) {
        return new PlaybackSourceSubtitleStream(index, codec, language, title);
    }

    static VideoConstraint videoConstraint(Integer maxWidth, Integer maxHeight, Integer maxBitrateKbps) {
        return new VideoConstraint(maxWidth, maxHeight, maxBitrateKbps);
    }

    static AudioConstraint audioConstraint(
        Integer maxBitrateKbps,
        Integer maxChannels,
        Integer preferredBitrateKbps,
        Integer maxSampleRateHz,
        Integer preferredSampleRateHz
    ) {
        return new AudioConstraint(
            maxBitrateKbps,
            maxChannels,
            preferredBitrateKbps,
            maxSampleRateHz,
            preferredSampleRateHz
        );
    }

    static MediaSessionPlaybackReport mediaSessionPlaybackReport(
        MediaSessionPlaybackEvent event,
        String objectId,
        MediaKind mediaKind,
        Long positionMillis,
        Long durationMillis,
        String occurredAt,
        String clientName,
        String deviceName,
        String playbackContext
    ) {
        return new MediaSessionPlaybackReport(
            event,
            objectId,
            mediaKind,
            positionMillis,
            durationMillis,
            occurredAt,
            clientName,
            deviceName,
            playbackContext
        );
    }

    static AudioFormatDescriptor audioFormatDescriptor(
        String container,
        String codec,
        String mimeType,
        Integer bitrateKbps,
        Integer channels,
        Integer sampleRateHz
    ) {
        return new AudioFormatDescriptor(container, codec, mimeType, bitrateKbps, channels, sampleRateHz);
    }

    static AudioNegotiationDecision audioNegotiationDecision(
        AudioDeliveryMode mode,
        Set<PlaybackReason> reasons,
        AudioFormatDescriptor source,
        AudioFormatDescriptor output
    ) {
        return new AudioNegotiationDecision(
            mode,
            reasons == null ? Set.of() : reasons,
            source,
            output
        );
    }

    static AudioNegotiationRequest audioNegotiationRequest(
        MediaSourceRef source,
        long startPositionMillis,
        AudioClientIdentity client,
        AudioCapabilitySet capabilities,
        AudioConstraint constraints,
        AudioOutputPreferences output
    ) {
        return new AudioNegotiationRequest(
            source,
            startPositionMillis,
            client,
            capabilities == null ? new AudioCapabilitySet() : capabilities,
            constraints == null ? new AudioConstraint() : constraints,
            output == null ? new AudioOutputPreferences() : output
        );
    }

    static MediaSessionTelemetry mediaSessionTelemetry(
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

    static PlaybackSession playbackSession(
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

    static AudioSession audioSession(
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
