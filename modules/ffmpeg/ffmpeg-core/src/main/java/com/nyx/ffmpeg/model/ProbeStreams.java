package com.nyx.ffmpeg.model;

import java.util.List;

public record ProbeStreams(
    List<VideoStream> video,
    List<AudioStream> audio,
    List<SubtitleStream> subtitle
) {
    public ProbeStreams {
        video = video == null ? List.of() : List.copyOf(video);
        audio = audio == null ? List.of() : List.copyOf(audio);
        subtitle = subtitle == null ? List.of() : List.copyOf(subtitle);
    }

    public List<VideoStream> getVideo() {
        return video;
    }

    public List<AudioStream> getAudio() {
        return audio;
    }

    public List<SubtitleStream> getSubtitle() {
        return subtitle;
    }
}
