package com.nyx.ffmpeg.model;

public interface Profile {
    String getName();

    VideoCodec getVideoCodec();

    AudioCodec getAudioCodec();

    SegmentDuration getSegmentDuration();

    default OutputFormat getDefaultOutputFormat() {
        return OutputFormat.Both;
    }
}
