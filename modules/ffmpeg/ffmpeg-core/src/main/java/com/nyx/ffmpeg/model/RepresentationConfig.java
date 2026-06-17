package com.nyx.ffmpeg.model;

public record RepresentationConfig(
    int width,
    int height,
    int bitrateKbps
) {
    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBitrateKbps() {
        return bitrateKbps;
    }
}
