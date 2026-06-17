package com.nyx.ffmpeg.model;

import java.util.List;

public record FfprobeOutput(
    FfprobeFormat format,
    List<FfprobeStream> streams
) {
    public FfprobeOutput {
        streams = streams == null ? List.of() : List.copyOf(streams);
    }

    public FfprobeOutput() {
        this(null, List.of());
    }

    public FfprobeFormat getFormat() {
        return format;
    }

    public List<FfprobeStream> getStreams() {
        return streams;
    }
}
