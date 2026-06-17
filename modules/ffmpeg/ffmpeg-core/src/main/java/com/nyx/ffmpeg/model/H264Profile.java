package com.nyx.ffmpeg.model;

public enum H264Profile {
    BASELINE("baseline"),
    MAIN("main"),
    HIGH("high");

    private final String value;

    H264Profile(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
