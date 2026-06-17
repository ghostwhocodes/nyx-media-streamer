package com.nyx.media.contracts;

import java.util.List;

public record CreateLibraryCollectionRequest(
    String title,
    String sortTitle,
    List<String> itemIds
) {
    public CreateLibraryCollectionRequest(String title) {
        this(title, null, List.of());
    }

    public CreateLibraryCollectionRequest(String title, String sortTitle) {
        this(title, sortTitle, List.of());
    }

    public CreateLibraryCollectionRequest {
        itemIds = ContractCollections.immutableList(itemIds);
    }
}
