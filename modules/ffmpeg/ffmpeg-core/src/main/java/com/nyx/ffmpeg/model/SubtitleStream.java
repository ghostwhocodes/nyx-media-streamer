package com.nyx.ffmpeg.model;

import java.util.Objects;

public record SubtitleStream(
    int index,
    String codec,
    String language,
    String title
) {
    public SubtitleStream {
        codec = Objects.requireNonNull(codec, "codec");
    }

    public SubtitleStream(int index, String codec) {
        this(index, codec, null, null);
    }

    public int getIndex() {
        return index;
    }

    public String getCodec() {
        return codec;
    }

    public String getLanguage() {
        return language;
    }

    public String getTitle() {
        return title;
    }
}
