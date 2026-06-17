package com.nyx.media.contracts;

import java.util.Set;

public record TrickplayRequest(
    Set<TrickplayAssetKind> assetKinds,
    Long intervalMillis,
    Integer thumbnailWidth,
    Integer thumbnailHeight,
    TrickplayTileLayoutRequest tileLayout
) {
    private static final Set<TrickplayAssetKind> DEFAULT_ASSET_KINDS =
        Set.of(TrickplayAssetKind.STORYBOARD_SHEET, TrickplayAssetKind.PREVIEW_STRIP);

    public TrickplayRequest() {
        this(DEFAULT_ASSET_KINDS, null, null, null, new TrickplayTileLayoutRequest());
    }

    public TrickplayRequest(Set<TrickplayAssetKind> assetKinds, Long intervalMillis, Integer thumbnailWidth, Integer thumbnailHeight) {
        this(assetKinds, intervalMillis, thumbnailWidth, thumbnailHeight, new TrickplayTileLayoutRequest());
    }

    public TrickplayRequest {
        if (assetKinds == null) {
            assetKinds = DEFAULT_ASSET_KINDS;
        } else {
            assetKinds = ContractCollections.immutableSet(assetKinds);
        }
        if (tileLayout == null) {
            tileLayout = new TrickplayTileLayoutRequest();
        }
    }
}
