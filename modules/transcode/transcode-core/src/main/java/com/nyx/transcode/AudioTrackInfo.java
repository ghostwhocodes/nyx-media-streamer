package com.nyx.transcode;

import java.util.Objects;

public record AudioTrackInfo(
    int trackIndex,
    String language,
    String title,
    int channels,
    String codec,
    boolean hasDownmix,
    int bitrateKbps
) {
    public AudioTrackInfo {
        codec = Objects.requireNonNull(codec, "codec");
    }

    public AudioTrackInfo(int trackIndex, String language, String title, int channels, String codec, boolean hasDownmix) {
        this(trackIndex, language, title, channels, codec, hasDownmix, 128);
    }

    public AudioTrackInfo(int trackIndex, String language, String title, int channels, String codec) {
        this(trackIndex, language, title, channels, codec, false, 128);
    }

    public int getTrackIndex() {
        return trackIndex;
    }

    public String getLanguage() {
        return language;
    }

    public String getTitle() {
        return title;
    }

    public int getChannels() {
        return channels;
    }

    public String getCodec() {
        return codec;
    }

    public boolean isHasDownmix() {
        return hasDownmix;
    }

    public int getBitrateKbps() {
        return bitrateKbps;
    }
}
