package com.nyx.media.contracts;

import java.util.List;

public record UpdateLibraryRequest(
    String name,
    String description,
    LibraryType type,
    List<LibrarySourceRootWriteRequest> sourceRoots
) {
    public UpdateLibraryRequest() {
        this(null, null, null, null);
    }

    public UpdateLibraryRequest {
        if (sourceRoots != null) {
            sourceRoots = ContractCollections.immutableList(sourceRoots);
        }
    }
}
