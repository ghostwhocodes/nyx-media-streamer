package com.nyx.media.contracts;

import java.util.List;

public record BrowseListing(
    List<MediaItem> items,
    int total,
    int page,
    int limit
) {
    public BrowseListing {
        items = ContractCollections.immutableList(items);
    }
}
