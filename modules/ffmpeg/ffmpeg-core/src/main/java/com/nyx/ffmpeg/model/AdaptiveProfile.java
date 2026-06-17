package com.nyx.ffmpeg.model;

import java.util.List;
import java.util.Objects;

public record AdaptiveProfile(
    String name,
    List<RepresentationConfig> representations,
    VideoCodec videoCodec,
    AudioCodec audioCodec,
    SegmentDuration segmentDuration
) implements Profile {
    public AdaptiveProfile {
        name = Objects.requireNonNull(name, "name");
        representations = representations == null ? List.of() : List.copyOf(representations);
        videoCodec = Objects.requireNonNull(videoCodec, "videoCodec");
        audioCodec = Objects.requireNonNull(audioCodec, "audioCodec");
        segmentDuration = Objects.requireNonNull(segmentDuration, "segmentDuration");
    }

    @Override
    public String getName() {
        return name;
    }

    public List<RepresentationConfig> getRepresentations() {
        return representations;
    }

    @Override
    public VideoCodec getVideoCodec() {
        return videoCodec;
    }

    @Override
    public AudioCodec getAudioCodec() {
        return audioCodec;
    }

    @Override
    public SegmentDuration getSegmentDuration() {
        return segmentDuration;
    }
}
