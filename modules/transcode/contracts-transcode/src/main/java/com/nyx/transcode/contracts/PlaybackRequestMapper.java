package com.nyx.transcode.contracts;

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
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.SubtitleSelection;
import com.nyx.playback.contracts.SubtitleSelectionMode;
import com.nyx.playback.contracts.TranscodePreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.stream.representation.contracts.StreamRepresentationTraits;

public final class PlaybackRequestMapper {
    private static final StreamRepresentationPolicy REPRESENTATION_POLICY = StreamRepresentationPolicy.defaultPolicy();

    private PlaybackRequestMapper() {
    }

    public static PlaybackRequest toPlaybackRequest(TranscodeRequest request) {
        List<RepresentationConstraint> explicitRepresentations = new ArrayList<>();
        if (request.getRepresentations() != null) {
            for (TranscodeRepresentation representation : request.getRepresentations()) {
                explicitRepresentations.add(
                    new RepresentationConstraint(
                        representation.width(),
                        representation.height(),
                        representation.bitrateKbps()
                    )
                );
            }
        }
        return new PlaybackRequest(
            new MediaSourceRef(request.getInputPath()),
            Math.round((request.getStartTimeSecs() == null ? 0.0 : request.getStartTimeSecs()) * 1000.0),
            new PlaybackOutputPreferences(
                traits(request.getRepresentation()).protocols(),
                traits(request.getRepresentation()).primaryProtocol(),
                request.getRepresentations() == null || !request.getRepresentations().isEmpty(),
                request.getRepresentation()
            ),
            null,
            null,
            new PlaybackSelection(
                toAudioTrackSelection(request.getAudioTracks()),
                toSubtitleSelection(request.getSubtitleMode(), request.getBurnSubtitleTrack())
            ),
            new com.nyx.playback.contracts.PlaybackConstraints(),
            new TranscodePreferences(
                request.getProfile(),
                toHardwareAccelerationPreference(request.getHwaccel()),
                explicitRepresentations
            )
        );
    }

    public static TranscodeRequest toTranscodeRequest(PlaybackRequest request) {
        List<RepresentationConstraint> explicitRepresentations = request.transcode().explicitRepresentations();
        List<TranscodeRepresentation> representations = null;
        if (explicitRepresentations != null && !explicitRepresentations.isEmpty()) {
            representations = explicitRepresentations.stream()
                .map(it -> new TranscodeRepresentation(it.width(), it.height(), it.bitrateKbps()))
                .toList();
        }
        return new TranscodeRequest(
            request.source().path(),
            request.startPositionMillis() / 1000.0,
            request.transcode().profileHint() == null ? "h264_fast" : request.transcode().profileHint(),
            toTranscodeRepresentation(request.output().preferredRepresentation(), request.output().allowedProtocols()),
            representations,
            toTranscodeSubtitleMode(request.selection().subtitles().mode()),
            request.selection().subtitles().trackIndex(),
            toTranscodeAudioTracks(request.selection().audio()),
            toTranscodeHwAccel(request.transcode().hardwareAcceleration()),
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        );
    }

    public static TranscodeRequest toTranscodeRequest(PlaybackRequest request, PlaybackDecision decision) {
        TranscodeExecutionMode executionMode = toTranscodeExecutionMode(decision.mode());
        List<TranscodeRepresentation> representations = null;
        if (executionMode != TranscodeExecutionMode.REMUX && executionMode != TranscodeExecutionMode.AUDIO_TRANSCODE) {
            List<RepresentationConstraint> explicitRepresentations = request.transcode().explicitRepresentations();
            if (explicitRepresentations != null && !explicitRepresentations.isEmpty()) {
                representations = explicitRepresentations.stream()
                    .map(it -> new TranscodeRepresentation(it.width(), it.height(), it.bitrateKbps()))
                    .toList();
            }
        }
        return new TranscodeRequest(
            request.source().path(),
            request.startPositionMillis() / 1000.0,
            request.transcode().profileHint() == null ? "h264_fast" : request.transcode().profileHint(),
            decision.stream().representation(),
            representations,
            toTranscodeSubtitleMode(request.selection().subtitles().mode()),
            request.selection().subtitles().trackIndex(),
            toTranscodeAudioTracks(request.selection().audio()),
            toTranscodeHwAccel(request.transcode().hardwareAcceleration()),
            executionMode,
            null
        );
    }

    private static StreamRepresentationTraits traits(StreamRepresentation representation) {
        return REPRESENTATION_POLICY.traits(representation);
    }

    private static SubtitleSelection toSubtitleSelection(String mode, Integer trackIndex) {
        String normalized = mode == null ? "" : mode.toLowerCase();
        return switch (normalized) {
            case "burn" -> new SubtitleSelection(SubtitleSelectionMode.BURN_IN, trackIndex);
            case "disable", "disabled", "none", "off" -> new SubtitleSelection(SubtitleSelectionMode.DISABLE, null);
            default -> new SubtitleSelection(SubtitleSelectionMode.EXTRACT, trackIndex);
        };
    }

    private static AudioTrackSelection toAudioTrackSelection(String audioTracks) {
        String normalized = audioTracks == null ? "" : audioTracks.toLowerCase();
        return switch (normalized) {
            case "all" -> new AudioTrackSelection(AudioTrackSelectionMode.ALL);
            case "default" -> new AudioTrackSelection(AudioTrackSelectionMode.DEFAULT);
            default -> {
                List<Integer> indices = new ArrayList<>();
                for (String segment : audioTracks.split(",")) {
                    try {
                        indices.add(Integer.parseInt(segment.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (!indices.isEmpty()) {
                    yield new AudioTrackSelection(AudioTrackSelectionMode.SPECIFIC, indices);
                }
                yield new AudioTrackSelection(AudioTrackSelectionMode.ALL);
            }
        };
    }

    private static HardwareAccelerationPreference toHardwareAccelerationPreference(String hwaccel) {
        String normalized = hwaccel == null ? "" : hwaccel.toLowerCase();
        return switch (normalized) {
            case "auto" -> HardwareAccelerationPreference.AUTO;
            case "off", "none", "disabled" -> HardwareAccelerationPreference.DISABLED;
            default -> HardwareAccelerationPreference.REQUIRED;
        };
    }

    private static StreamRepresentation toTranscodeRepresentation(
        StreamRepresentation preferredRepresentation,
        Set<StreamingProtocol> protocols
    ) {
        if (preferredRepresentation != null) {
            return preferredRepresentation;
        }
        Set<StreamingProtocol> normalized = protocols == null ? Set.of() : Set.copyOf(protocols);
        if (normalized.contains(StreamingProtocol.HLS) && normalized.contains(StreamingProtocol.DASH)) {
            return StreamRepresentation.HLS_DASH_FMP4;
        }
        if (normalized.contains(StreamingProtocol.DASH)) {
            return StreamRepresentation.DASH_FMP4;
        }
        return StreamRepresentation.HLS_FMP4;
    }

    private static String toTranscodeSubtitleMode(SubtitleSelectionMode mode) {
        return switch (mode) {
            case EXTRACT -> "extract";
            case BURN_IN -> "burn";
            case DISABLE -> "disable";
        };
    }

    private static String toTranscodeAudioTracks(AudioTrackSelection selection) {
        return switch (selection.mode()) {
            case ALL -> "all";
            case DEFAULT -> "default";
            case SPECIFIC -> String.join(",", selection.trackIndices().stream().map(String::valueOf).toList());
        };
    }

    private static String toTranscodeHwAccel(HardwareAccelerationPreference preference) {
        return switch (preference) {
            case AUTO, REQUIRED -> "auto";
            case DISABLED -> "none";
        };
    }

    private static TranscodeExecutionMode toTranscodeExecutionMode(PlaybackMode mode) {
        return switch (mode) {
            case REMUX -> TranscodeExecutionMode.REMUX;
            case AUDIO_TRANSCODE -> TranscodeExecutionMode.AUDIO_TRANSCODE;
            case VIDEO_TRANSCODE -> TranscodeExecutionMode.VIDEO_TRANSCODE;
            case SUBTITLE_BURN_IN -> TranscodeExecutionMode.SUBTITLE_BURN_IN;
            case DIRECT_PLAY -> throw new IllegalStateException("Direct-play decisions do not map to transcode requests");
        };
    }
}
