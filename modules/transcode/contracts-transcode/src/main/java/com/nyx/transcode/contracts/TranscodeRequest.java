package com.nyx.transcode.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import java.util.List;

public record TranscodeRequest(
    @JsonProperty("input_path") String inputPath,
    @JsonProperty("start_time_secs") Double startTimeSecs,
    @JsonProperty("profile") String profile,
    @JsonIgnore StreamRepresentation representation,
    @JsonProperty("representations") List<TranscodeRepresentation> representations,
    @JsonProperty("subtitle_mode") String subtitleMode,
    @JsonProperty("burn_subtitle_track") Integer burnSubtitleTrack,
    @JsonProperty("audio_tracks") String audioTracks,
    @JsonProperty("hw_accel") String hwaccel,
    @JsonIgnore TranscodeExecutionMode executionMode,
    @JsonIgnore String specKey
) {
    private static final StreamRepresentationPolicy REPRESENTATION_POLICY = StreamRepresentationPolicy.defaultPolicy();

    @JsonCreator
    public TranscodeRequest(
        @JsonProperty("input_path") String inputPath,
        @JsonProperty("start_time_secs") Double startTimeSecs,
        @JsonProperty("profile") String profile,
        @JsonProperty("format") String format,
        @JsonProperty("representations") List<TranscodeRepresentation> representations,
        @JsonProperty("subtitle_mode") String subtitleMode,
        @JsonProperty("burn_subtitle_track") Integer burnSubtitleTrack,
        @JsonProperty("audio_tracks") String audioTracks,
        @JsonProperty("hw_accel") String hwaccel
    ) {
        this(
            inputPath,
            startTimeSecs,
            profile,
            REPRESENTATION_POLICY.normalizeExternalName(format),
            representations,
            subtitleMode,
            burnSubtitleTrack,
            audioTracks,
            hwaccel,
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        );
    }

    public TranscodeRequest(String inputPath) {
        this(
            inputPath,
            null,
            "h264_fast",
            StreamRepresentation.HLS_DASH_FMP4,
            null,
            "extract",
            null,
            "all",
            "auto",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        );
    }

    public TranscodeRequest {
        profile = profile == null ? "h264_fast" : profile;
        representation = representation == null ? StreamRepresentation.HLS_DASH_FMP4 : representation;
        representations = representations == null ? null : List.copyOf(representations);
        subtitleMode = subtitleMode == null ? "extract" : subtitleMode;
        audioTracks = audioTracks == null ? "all" : audioTracks;
        hwaccel = hwaccel == null ? "auto" : hwaccel;
        executionMode = executionMode == null ? TranscodeExecutionMode.VIDEO_TRANSCODE : executionMode;
        specKey = specKey == null
            ? TranscodeContracts.buildTranscodeSpecKey(
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
            )
            : specKey;
    }

    @JsonIgnore
    public String getInputPath() {
        return inputPath;
    }

    @JsonIgnore
    public Double getStartTimeSecs() {
        return startTimeSecs;
    }

    @JsonIgnore
    public String getProfile() {
        return profile;
    }

    @JsonIgnore
    public StreamRepresentation getRepresentation() {
        return representation;
    }

    @JsonIgnore
    public List<TranscodeRepresentation> getRepresentations() {
        return representations;
    }

    @JsonIgnore
    public String getSubtitleMode() {
        return subtitleMode;
    }

    @JsonIgnore
    public Integer getBurnSubtitleTrack() {
        return burnSubtitleTrack;
    }

    @JsonIgnore
    public String getAudioTracks() {
        return audioTracks;
    }

    @JsonIgnore
    public String getHwaccel() {
        return hwaccel;
    }

    @JsonIgnore
    public TranscodeExecutionMode getExecutionMode() {
        return executionMode;
    }

    @JsonIgnore
    public String getSpecKey() {
        return specKey;
    }
}
