package com.nyx.transcode.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

public final class TranscodeContracts {
    private static final ObjectMapper TRANSCODE_SPEC_MAPPER = NyxJson.newMapper();
    private static final StreamRepresentationPolicy REPRESENTATION_POLICY = StreamRepresentationPolicy.defaultPolicy();

    private TranscodeContracts() {
    }

    public static boolean canTransitionTo(JobStatus current, JobStatus next) {
        return switch (current) {
            case QUEUED -> next == JobStatus.PROBING;
            case PROBING -> next == JobStatus.TRANSCODING || next == JobStatus.FAILED;
            case TRANSCODING -> next == JobStatus.COMPLETED
                || next == JobStatus.FAILED
                || next == JobStatus.CANCELLED
                || next == JobStatus.RETRYING;
            case RETRYING -> next == JobStatus.TRANSCODING
                || next == JobStatus.FAILED
                || next == JobStatus.CANCELLED;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }

    public static String buildTranscodeSpecKey(
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
        try {
            return TRANSCODE_SPEC_MAPPER.writeValueAsString(
                new TranscodeSpecIdentity(
                    inputPath,
                    normalizeDecimal(startTimeSecs),
                    profile,
                    REPRESENTATION_POLICY.storageToken(representation).value(),
                    executionMode.name(),
                    normalizeRepresentations(representations),
                    subtitleMode,
                    burnSubtitleTrack,
                    audioTracks,
                    normalizeHwAccelForSpecKey(executionMode, hwaccel)
                )
            );
        } catch (Exception exception) {
            throw new RuntimeException("Failed to build transcode spec key", exception);
        }
    }

    private static String normalizeDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static List<TranscodeRepresentation> normalizeRepresentations(List<TranscodeRepresentation> representations) {
        if (representations == null || representations.isEmpty()) {
            return List.of();
        }
        return representations.stream()
            .sorted(
                Comparator.comparingInt(TranscodeRepresentation::width)
                    .thenComparingInt(TranscodeRepresentation::height)
                    .thenComparingInt(TranscodeRepresentation::bitrateKbps)
            )
            .toList();
    }

    private static String normalizeHwAccelForSpecKey(TranscodeExecutionMode executionMode, String hwaccel) {
        return switch (executionMode) {
            case REMUX, AUDIO_TRANSCODE -> "none";
            default -> hwaccel;
        };
    }

    private record TranscodeSpecIdentity(
        String inputPath,
        String startTimeSecs,
        String profile,
        String representation,
        String executionMode,
        List<TranscodeRepresentation> representations,
        String subtitleMode,
        Integer burnSubtitleTrack,
        String audioTracks,
        String hwaccel
    ) {
    }
}
