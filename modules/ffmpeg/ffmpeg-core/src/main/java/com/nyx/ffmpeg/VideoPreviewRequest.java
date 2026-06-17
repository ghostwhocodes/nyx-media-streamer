package com.nyx.ffmpeg;

public record VideoPreviewRequest(
    Long positionMillis,
    Integer percent,
    Integer width,
    Integer height
) {
    public VideoPreviewRequest() {
        this(null, null, null, null);
    }

    public Long getPositionMillis() {
        return positionMillis;
    }

    public Integer getPercent() {
        return percent;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }
}
