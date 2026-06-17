package com.nyx.media.contracts;

import java.util.List;

public record TrickplayManifest(
    long durationMillis,
    TrickplayRequest request,
    long intervalMillis,
    List<TrickplayAsset> assets,
    List<TrickplayTimelineEntry> timeline,
    Boolean cacheable
) {
    public TrickplayManifest(long durationMillis, long intervalMillis) {
        this(durationMillis, new TrickplayRequest(), intervalMillis, List.of(), List.of(), true);
    }

    public TrickplayManifest(long durationMillis, TrickplayRequest request, long intervalMillis) {
        this(durationMillis, request, intervalMillis, List.of(), List.of(), true);
    }

    public TrickplayManifest {
        if (request == null) {
            request = new TrickplayRequest();
        }
        assets = ContractCollections.immutableList(assets);
        timeline = ContractCollections.immutableList(timeline);
        if (cacheable == null) {
            cacheable = true;
        }
    }
}
