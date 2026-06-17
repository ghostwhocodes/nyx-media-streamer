package com.nyx.ffmpeg;

public record VideoTrickplayTimelineEntry(
    long positionMillis,
    TrickplayAssetKind kind,
    int assetIndex,
    int column,
    int row
) {
}
