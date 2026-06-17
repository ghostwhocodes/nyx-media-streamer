package com.nyx.config;

public record AudioConfig(
    String aacBitrate,
    String opusBitrate,
    String mp3Bitrate,
    long processTimeoutSeconds
) {
    public AudioConfig {
        aacBitrate = aacBitrate == null ? "256k" : aacBitrate;
        opusBitrate = opusBitrate == null ? "128k" : opusBitrate;
        mp3Bitrate = mp3Bitrate == null ? "192k" : mp3Bitrate;
    }

    public AudioConfig() {
        this("256k", "128k", "192k", 30L);
    }

    public String getAacBitrate() {
        return aacBitrate;
    }

    public String getOpusBitrate() {
        return opusBitrate;
    }

    public String getMp3Bitrate() {
        return mp3Bitrate;
    }

    public long getProcessTimeoutSeconds() {
        return processTimeoutSeconds;
    }
}
