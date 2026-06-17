package com.nyx.media.contracts;

public record TrickplayTimelineEntry(
    long positionMillis,
    TrickplayAssetKind kind,
    int assetIndex,
    int column,
    int row
) {
    public TrickplayTimelineEntry(long positionMillis, int assetIndex) {
        this(positionMillis, TrickplayAssetKind.STORYBOARD_SHEET, assetIndex, 0, 0);
    }

    public TrickplayTimelineEntry(long positionMillis, TrickplayAssetKind kind, int assetIndex) {
        this(positionMillis, kind, assetIndex, 0, 0);
    }

    public TrickplayTimelineEntry {
        if (kind == null) {
            kind = TrickplayAssetKind.STORYBOARD_SHEET;
        }
    }
}
