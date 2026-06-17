package com.nyx.media.contracts;

import java.util.List;

public record LibraryCollection(
    String collectionId,
    String libraryId,
    String title,
    String sortTitle,
    List<String> itemIds,
    Integer itemCount,
    String createdAt,
    String updatedAt
) {
    public LibraryCollection(
        String collectionId,
        String libraryId,
        String title,
        List<String> itemIds,
        String createdAt,
        String updatedAt
    ) {
        this(collectionId, libraryId, title, null, itemIds, null, createdAt, updatedAt);
    }

    public LibraryCollection {
        itemIds = ContractCollections.immutableList(itemIds);
        if (itemCount == null) {
            itemCount = itemIds.size();
        }
    }
}
