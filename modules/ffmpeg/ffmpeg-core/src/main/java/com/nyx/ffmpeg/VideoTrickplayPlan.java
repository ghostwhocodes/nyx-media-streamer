package com.nyx.ffmpeg;

import java.util.List;

public record VideoTrickplayPlan(
    int sourceWidth,
    int sourceHeight,
    long durationMillis,
    long intervalMillis,
    int thumbnailWidth,
    int thumbnailHeight,
    int tileColumns,
    int tileRows,
    List<VideoTrickplayAssetPlan> assets,
    List<VideoTrickplayTimelineEntry> timeline
) {
    public VideoTrickplayPlan(
        int sourceWidth,
        int sourceHeight,
        long durationMillis,
        long intervalMillis,
        int thumbnailWidth,
        int thumbnailHeight,
        int tileColumns,
        int tileRows,
        List<VideoTrickplayAssetPlan> assets
    ) {
        this(sourceWidth, sourceHeight, durationMillis, intervalMillis, thumbnailWidth, thumbnailHeight, tileColumns, tileRows, assets, List.of());
    }

    public VideoTrickplayPlan {
        assets = List.copyOf(assets);
        timeline = List.copyOf(timeline);
    }

    public int getTotalFrames() {
        return assets.stream().mapToInt(VideoTrickplayAssetPlan::frameCount).sum();
    }

    public String getCacheKey() {
        return "dur-" + durationMillis
            + "_src-" + sourceWidth + 'x' + sourceHeight
            + "_interval-" + intervalMillis
            + "_thumb-" + thumbnailWidth + 'x' + thumbnailHeight
            + "_tiles-" + tileColumns + 'x' + tileRows
            + "_assets-" + assets.stream().map(VideoTrickplayAssetPlan::getCacheKey).reduce((a, b) -> a + "." + b).orElse("");
    }
}
