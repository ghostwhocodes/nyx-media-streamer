package com.nyx.ffmpeg.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AudioStream(
    int index,
    String codec,
    int channels,
    Integer bitrateKbps,
    Integer sampleRateHz,
    String language,
    String title
) {
    public AudioStream {
        codec = Objects.requireNonNull(codec, "codec");
    }

    public AudioStream(int index, String codec, int channels) {
        this(index, codec, channels, null, null, null, null);
    }

    public AudioStream(int index, String codec, int channels, Integer bitrateKbps) {
        this(index, codec, channels, bitrateKbps, null, null, null);
    }

    public int getIndex() {
        return index;
    }

    public String getCodec() {
        return codec;
    }

    public int getChannels() {
        return channels;
    }

    public Integer getBitrateKbps() {
        return bitrateKbps;
    }

    public Integer getSampleRateHz() {
        return sampleRateHz;
    }

    public String getLanguage() {
        return language;
    }

    public String getTitle() {
        return title;
    }
}
