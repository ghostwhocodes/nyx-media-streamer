package com.nyx.transcode;

import java.util.Objects;

public record HlsVariantStream(
    int bandwidth,
    String uri,
    Integer width,
    Integer height,
    String audioGroupId,
    String subtitleGroupId
) {
    public HlsVariantStream {
        uri = Objects.requireNonNull(uri, "uri");
    }

    public HlsVariantStream(int bandwidth, String uri) {
        this(bandwidth, uri, null, null, null, null);
    }

    public HlsVariantStream(int bandwidth, String uri, Integer width, Integer height) {
        this(bandwidth, uri, width, height, null, null);
    }

    public int getBandwidth() {
        return bandwidth;
    }

    public String getUri() {
        return uri;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public String getAudioGroupId() {
        return audioGroupId;
    }

    public String getSubtitleGroupId() {
        return subtitleGroupId;
    }
}
