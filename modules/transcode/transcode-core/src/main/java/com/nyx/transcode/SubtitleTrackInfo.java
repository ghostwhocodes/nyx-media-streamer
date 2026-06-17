package com.nyx.transcode;

public record SubtitleTrackInfo(
    int trackIndex,
    String language,
    String title
) {
    public int getTrackIndex() {
        return trackIndex;
    }

    public String getLanguage() {
        return language;
    }

    public String getTitle() {
        return title;
    }
}
