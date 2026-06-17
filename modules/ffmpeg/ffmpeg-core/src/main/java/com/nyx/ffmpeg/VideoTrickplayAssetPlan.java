package com.nyx.ffmpeg;

public record VideoTrickplayAssetPlan(
    TrickplayAssetKind kind,
    int assetIndex,
    long startMillis,
    long endMillis,
    long intervalMillis,
    int frameCount,
    int tileColumns,
    int tileRows,
    int thumbnailWidth,
    int thumbnailHeight,
    int outputWidth,
    int outputHeight,
    String outputFormat,
    String outputExtension,
    String outputMimeType
) {
    public VideoTrickplayAssetPlan(
        TrickplayAssetKind kind,
        int assetIndex,
        long startMillis,
        long endMillis,
        long intervalMillis,
        int frameCount,
        int tileColumns,
        int tileRows,
        int thumbnailWidth,
        int thumbnailHeight,
        int outputWidth,
        int outputHeight
    ) {
        this(
            kind,
            assetIndex,
            startMillis,
            endMillis,
            intervalMillis,
            frameCount,
            tileColumns,
            tileRows,
            thumbnailWidth,
            thumbnailHeight,
            outputWidth,
            outputHeight,
            "jpeg",
            "jpg",
            "image/jpeg"
        );
    }

    public String getCacheKey() {
        return kind.name().toLowerCase()
            + "_asset-" + assetIndex
            + "_range-" + startMillis + '-' + endMillis
            + "_interval-" + intervalMillis
            + "_frames-" + frameCount
            + "_tiles-" + tileColumns + 'x' + tileRows
            + "_thumb-" + thumbnailWidth + 'x' + thumbnailHeight
            + "_out-" + outputWidth + 'x' + outputHeight
            + "_fmt-" + outputExtension;
    }

    public TrickplayAssetKind getKind() {
        return kind;
    }

    public int getAssetIndex() {
        return assetIndex;
    }

    public long getStartMillis() {
        return startMillis;
    }

    public long getEndMillis() {
        return endMillis;
    }

    public long getIntervalMillis() {
        return intervalMillis;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public int getTileColumns() {
        return tileColumns;
    }

    public int getTileRows() {
        return tileRows;
    }

    public int getThumbnailWidth() {
        return thumbnailWidth;
    }

    public int getThumbnailHeight() {
        return thumbnailHeight;
    }

    public int getOutputWidth() {
        return outputWidth;
    }

    public int getOutputHeight() {
        return outputHeight;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public String getOutputExtension() {
        return outputExtension;
    }

    public String getOutputMimeType() {
        return outputMimeType;
    }
}
