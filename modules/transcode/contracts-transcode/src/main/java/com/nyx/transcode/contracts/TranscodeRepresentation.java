package com.nyx.transcode.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TranscodeRepresentation(
    @JsonProperty("width") int width,
    @JsonProperty("height") int height,
    @JsonProperty("bitrateKbps") int bitrateKbps
) {
    @JsonIgnore
    public int getWidth() {
        return width;
    }

    @JsonIgnore
    public int getHeight() {
        return height;
    }

    @JsonIgnore
    public int getBitrateKbps() {
        return bitrateKbps;
    }
}
