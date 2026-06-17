package com.nyx.ffmpeg.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VideoStream(
    int index,
    String codec,
    int width,
    int height,
    double fps,
    Integer bitrateKbps
) {
    public VideoStream {
        codec = Objects.requireNonNull(codec, "codec");
    }

    public VideoStream(int index, String codec, int width, int height, double fps) {
        this(index, codec, width, height, fps, null);
    }

    public int getIndex() {
        return index;
    }

    public String getCodec() {
        return codec;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public double getFps() {
        return fps;
    }

    public Integer getBitrateKbps() {
        return bitrateKbps;
    }
}
