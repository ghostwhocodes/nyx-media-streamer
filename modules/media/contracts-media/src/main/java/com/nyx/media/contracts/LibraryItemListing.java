package com.nyx.media.contracts;

import java.util.List;

public record LibraryItemListing(
    List<LibraryItem> items,
    int total
) {
    public LibraryItemListing(int total) {
        this(List.of(), total);
    }

    public LibraryItemListing {
        items = ContractCollections.immutableList(items);
    }
}
