package com.nyx.ffmpeg.model;

import java.util.Objects;

public record CmafProfile(
    String name,
    VideoCodec videoCodec,
    AudioCodec audioCodec,
    SegmentDuration segmentDuration
) implements Profile {
    public CmafProfile {
        name = Objects.requireNonNull(name, "name");
        videoCodec = Objects.requireNonNull(videoCodec, "videoCodec");
        audioCodec = Objects.requireNonNull(audioCodec, "audioCodec");
        segmentDuration = Objects.requireNonNull(segmentDuration, "segmentDuration");
    }

    @Override
    public String getName() {
        return name;
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

    @Override
    public OutputFormat getDefaultOutputFormat() {
        return OutputFormat.Cmaf;
    }
}
