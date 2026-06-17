package com.nyx.transcode.contracts;

import com.nyx.media.contracts.MediaKind;
import com.nyx.playback.contracts.AudioTrackSelection;
import com.nyx.playback.contracts.AudioTrackSelectionMode;
import com.nyx.playback.contracts.HardwareAccelerationPreference;
import com.nyx.playback.contracts.MediaSourceRef;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackOutputPreferences;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSelection;
import com.nyx.playback.contracts.RepresentationConstraint;
import com.nyx.playback.contracts.StreamDescriptor;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.SubtitleSelection;
import com.nyx.playback.contracts.SubtitleSelectionMode;
import com.nyx.playback.contracts.TranscodePreferences;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ContractFactories {
    private ContractFactories() {
    }

    static boolean canTransitionTo(JobStatus current, JobStatus next) {
        return TranscodeContracts.canTransitionTo(current, next);
    }

    static String buildTranscodeSpecKey(
        String inputPath,
        Double startTimeSecs,
        String profile,
        StreamRepresentation representation,
        TranscodeExecutionMode executionMode,
        List<TranscodeRepresentation> representations,
        String subtitleMode,
        Integer burnSubtitleTrack,
        String audioTracks,
        String hwaccel
    ) {
        return TranscodeContracts.buildTranscodeSpecKey(
            inputPath,
            startTimeSecs,
            profile,
            representation,
            executionMode,
            representations,
            subtitleMode,
            burnSubtitleTrack,
            audioTracks,
            hwaccel
        );
    }

    static PlaybackRequest toPlaybackRequest(TranscodeRequest request) {
        return PlaybackRequestMapper.toPlaybackRequest(request);
    }

    static TranscodeRequest toTranscodeRequest(PlaybackRequest request) {
        return PlaybackRequestMapper.toTranscodeRequest(request);
    }

    static TranscodeRequest toTranscodeRequest(PlaybackRequest request, PlaybackDecision decision) {
        return PlaybackRequestMapper.toTranscodeRequest(request, decision);
    }

    static TranscodeRequest transcodeRequest(String inputPath) {
        return new TranscodeRequest(inputPath);
    }

    static TranscodeRequest transcodeRequest(
        String inputPath,
        Double startTimeSecs,
        String profile,
        StreamRepresentation representation,
        List<TranscodeRepresentation> representations,
        String subtitleMode,
        Integer burnSubtitleTrack,
        String audioTracks,
        String hwaccel,
        TranscodeExecutionMode executionMode,
        String specKey
    ) {
        return new TranscodeRequest(
            inputPath,
            startTimeSecs,
            profile,
            representation,
            representations,
            subtitleMode,
            burnSubtitleTrack,
            audioTracks,
            hwaccel,
            executionMode,
            specKey
        );
    }

    static TranscodeRepresentation transcodeRepresentation(int width, int height, int bitrateKbps) {
        return new TranscodeRepresentation(width, height, bitrateKbps);
    }

    static MediaSourceRef mediaSourceRef(String path) {
        return new MediaSourceRef(path);
    }

    static MediaSourceRef mediaSourceRef(
        String path,
        com.nyx.playback.contracts.PlaybackSourceCharacteristics characteristics,
        String objectId,
        MediaKind mediaKind
    ) {
        return new MediaSourceRef(path, characteristics, objectId, mediaKind);
    }

    static PlaybackOutputPreferences playbackOutputPreferences() {
        return new PlaybackOutputPreferences(new LinkedHashSet<>(List.of(StreamingProtocol.HLS, StreamingProtocol.DASH)), null, true);
    }

    static PlaybackOutputPreferences playbackOutputPreferences(
        Set<StreamingProtocol> allowedProtocols,
        StreamingProtocol preferredProtocol
    ) {
        return new PlaybackOutputPreferences(allowedProtocols, preferredProtocol, true);
    }

    static PlaybackOutputPreferences playbackOutputPreferences(
        Set<StreamingProtocol> allowedProtocols,
        StreamingProtocol preferredProtocol,
        boolean allowAdaptiveStreaming
    ) {
        return new PlaybackOutputPreferences(allowedProtocols, preferredProtocol, allowAdaptiveStreaming);
    }

    static AudioTrackSelection audioTrackSelection() {
        return new AudioTrackSelection();
    }

    static AudioTrackSelection audioTrackSelection(AudioTrackSelectionMode mode, List<Integer> trackIndices) {
        if ((trackIndices == null || trackIndices.isEmpty()) && mode != AudioTrackSelectionMode.SPECIFIC) {
            return new AudioTrackSelection(mode);
        }
        return new AudioTrackSelection(mode, trackIndices);
    }

    static SubtitleSelection subtitleSelection() {
        return new SubtitleSelection();
    }

    static SubtitleSelection subtitleSelection(SubtitleSelectionMode mode, Integer trackIndex) {
        return new SubtitleSelection(mode, trackIndex);
    }

    static PlaybackSelection playbackSelection() {
        return new PlaybackSelection();
    }

    static PlaybackSelection playbackSelection(AudioTrackSelection audio, SubtitleSelection subtitles) {
        return new PlaybackSelection(audio, subtitles);
    }

    static RepresentationConstraint representationConstraint(int width, int height, int bitrateKbps) {
        return new RepresentationConstraint(width, height, bitrateKbps);
    }

    static TranscodePreferences transcodePreferences() {
        return new TranscodePreferences();
    }

    static TranscodePreferences transcodePreferences(
        String profileHint,
        HardwareAccelerationPreference hardwareAcceleration,
        List<RepresentationConstraint> explicitRepresentations
    ) {
        return new TranscodePreferences(profileHint, hardwareAcceleration, explicitRepresentations);
    }

    static PlaybackRequest playbackRequest(MediaSourceRef source) {
        return new PlaybackRequest(source);
    }

    static PlaybackRequest playbackRequest(
        MediaSourceRef source,
        long startPositionMillis,
        PlaybackOutputPreferences output,
        PlaybackSelection selection,
        TranscodePreferences transcode
    ) {
        return new PlaybackRequest(
            source,
            startPositionMillis,
            output,
            null,
            null,
            selection,
            new com.nyx.playback.contracts.PlaybackConstraints(),
            transcode
        );
    }

    static StreamDescriptor streamDescriptor(StreamingProtocol protocol) {
        return new StreamDescriptor(protocol);
    }

    static StreamDescriptor streamDescriptor(StreamingProtocol protocol, boolean adaptive) {
        return new StreamDescriptor(protocol, null, adaptive);
    }

    static PlaybackDecision playbackDecision(PlaybackMode mode, StreamDescriptor stream) {
        return new PlaybackDecision(mode, stream);
    }
}
