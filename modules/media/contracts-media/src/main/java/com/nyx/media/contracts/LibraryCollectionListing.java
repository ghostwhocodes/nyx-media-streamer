package com.nyx.media.contracts;

import java.util.List;

public record LibraryCollectionListing(
    List<LibraryCollection> collections,
    int total
) {
    public LibraryCollectionListing(int total) {
        this(List.of(), total);
    }

    public LibraryCollectionListing {
        collections = ContractCollections.immutableList(collections);
    }
}
