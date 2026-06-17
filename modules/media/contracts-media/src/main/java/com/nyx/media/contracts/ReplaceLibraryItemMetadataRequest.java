package com.nyx.media.contracts;

import java.util.List;

public record ReplaceLibraryItemMetadataRequest(
    String displayTitle,
    String sortTitle,
    String overview,
    List<String> tags
) {
    public ReplaceLibraryItemMetadataRequest() {
        this(null, null, null, List.of());
    }

    public ReplaceLibraryItemMetadataRequest {
        tags = ContractCollections.immutableList(tags);
    }
}
