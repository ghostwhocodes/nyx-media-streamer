package com.nyx.media.contracts;

public record TrickplayAsset(
    TrickplayAssetKind kind,
    String url,
    String mimeType,
    ImageDimensions dimensions,
    ImageDimensions thumbnailDimensions,
    long intervalMillis,
    long startMillis,
    long endMillis,
    int frameCount,
    TrickplayTileLayout tileLayout,
    Boolean cacheable
) {
    public TrickplayAsset(
        TrickplayAssetKind kind,
        String url,
        String mimeType,
        ImageDimensions dimensions,
        ImageDimensions thumbnailDimensions,
        long intervalMillis,
        long startMillis,
        long endMillis,
        int frameCount
    ) {
        this(kind, url, mimeType, dimensions, thumbnailDimensions, intervalMillis, startMillis, endMillis, frameCount, null, true);
    }

    public TrickplayAsset(
        TrickplayAssetKind kind,
        String url,
        String mimeType,
        ImageDimensions dimensions,
        ImageDimensions thumbnailDimensions,
        long intervalMillis,
        long startMillis,
        long endMillis,
        int frameCount,
        TrickplayTileLayout tileLayout
    ) {
        this(kind, url, mimeType, dimensions, thumbnailDimensions, intervalMillis, startMillis, endMillis, frameCount, tileLayout, true);
    }

    public TrickplayAsset {
        if (cacheable == null) {
            cacheable = true;
        }
    }
}
