package com.nyx.media.contracts;

import java.util.List;

public record AudioListing(
    List<MediaItem.Music> tracks,
    int total,
    int page,
    int limit
) {
    public AudioListing {
        tracks = ContractCollections.immutableList(tracks);
    }
}
