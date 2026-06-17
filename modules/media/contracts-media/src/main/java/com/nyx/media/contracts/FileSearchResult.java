package com.nyx.media.contracts;

import java.util.List;

public record FileSearchResult(
    List<MediaItem> items,
    int total,
    int page,
    int limit,
    String query
) {
    public FileSearchResult {
        items = ContractCollections.immutableList(items);
    }
}
