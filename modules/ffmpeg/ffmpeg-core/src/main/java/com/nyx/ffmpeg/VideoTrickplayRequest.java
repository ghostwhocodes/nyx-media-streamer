package com.nyx.ffmpeg;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record VideoTrickplayRequest(
    Set<TrickplayAssetKind> assetKinds,
    Long intervalMillis,
    Integer thumbnailWidth,
    Integer thumbnailHeight,
    Integer tileColumns,
    Integer tileRows
) {
    public VideoTrickplayRequest() {
        this(
            Set.of(TrickplayAssetKind.STORYBOARD_SHEET, TrickplayAssetKind.PREVIEW_STRIP),
            null,
            null,
            null,
            null,
            null
        );
    }

    public VideoTrickplayRequest {
        if (assetKinds == null) {
            assetKinds = Set.of(TrickplayAssetKind.STORYBOARD_SHEET, TrickplayAssetKind.PREVIEW_STRIP);
        } else if (assetKinds.isEmpty()) {
            assetKinds = Set.of();
        } else {
            assetKinds = Collections.unmodifiableSet(new LinkedHashSet<>(assetKinds));
        }
    }

    public Set<TrickplayAssetKind> getAssetKinds() {
        return assetKinds;
    }

    public Long getIntervalMillis() {
        return intervalMillis;
    }

    public Integer getThumbnailWidth() {
        return thumbnailWidth;
    }

    public Integer getThumbnailHeight() {
        return thumbnailHeight;
    }

    public Integer getTileColumns() {
        return tileColumns;
    }

    public Integer getTileRows() {
        return tileRows;
    }
}
