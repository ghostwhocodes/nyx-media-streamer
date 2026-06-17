package com.nyx.media.contracts;

import java.util.List;

public record LibraryItemUserStateListing(
    List<LibraryItemUserStateEntry> items,
    int total,
    int page,
    int limit
) {
    public LibraryItemUserStateListing(int total, int page, int limit) {
        this(List.of(), total, page, limit);
    }

    public LibraryItemUserStateListing {
        items = ContractCollections.immutableList(items);
    }
}
