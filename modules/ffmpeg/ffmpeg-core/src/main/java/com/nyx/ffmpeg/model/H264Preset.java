package com.nyx.ffmpeg.model;

public enum H264Preset {
    ULTRAFAST("ultrafast"),
    SUPERFAST("superfast"),
    VERYFAST("veryfast"),
    FASTER("faster"),
    FAST("fast"),
    MEDIUM("medium"),
    SLOW("slow"),
    SLOWER("slower"),
    VERYSLOW("veryslow");

    private final String value;

    H264Preset(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
