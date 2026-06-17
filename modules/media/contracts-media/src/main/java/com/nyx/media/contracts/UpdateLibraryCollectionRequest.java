package com.nyx.media.contracts;

import java.util.List;

public record UpdateLibraryCollectionRequest(
    String title,
    String sortTitle,
    List<String> itemIds
) {
    public UpdateLibraryCollectionRequest(String title) {
        this(title, null, List.of());
    }

    public UpdateLibraryCollectionRequest(String title, String sortTitle) {
        this(title, sortTitle, List.of());
    }

    public UpdateLibraryCollectionRequest {
        itemIds = ContractCollections.immutableList(itemIds);
    }
}
