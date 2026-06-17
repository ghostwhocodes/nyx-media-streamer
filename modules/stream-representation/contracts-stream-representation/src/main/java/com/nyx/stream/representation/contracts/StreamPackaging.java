package com.nyx.stream.representation.contracts;

public enum StreamPackaging {
    NONE("none"),
    FMP4("fmp4"),
    MPEG_TS("mpegts");

    private final String token;

    StreamPackaging(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }
}
