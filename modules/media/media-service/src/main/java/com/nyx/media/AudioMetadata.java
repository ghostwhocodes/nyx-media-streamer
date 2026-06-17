package com.nyx.media;

public record AudioMetadata(
    Double duration,
    Long bitrate,
    Integer channels,
    String artist,
    String album,
    String title
) {
    public AudioMetadata() {
        this(null, null, null, null, null, null);
    }

    public Double getDuration() {
        return duration;
    }

    public Long getBitrate() {
        return bitrate;
    }

    public Integer getChannels() {
        return channels;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public String getTitle() {
        return title;
    }
}
