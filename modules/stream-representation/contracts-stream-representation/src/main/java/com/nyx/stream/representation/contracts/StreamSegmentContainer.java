package com.nyx.stream.representation.contracts;

public enum StreamSegmentContainer {
    NONE("none", ""),
    FMP4("fmp4", "m4s"),
    MPEG_TS("mpegts", "ts");

    private final String token;
    private final String fileExtension;

    StreamSegmentContainer(String token, String fileExtension) {
        this.token = token;
        this.fileExtension = fileExtension;
    }

    public String token() {
        return token;
    }

    public String fileExtension() {
        return fileExtension;
    }
}
