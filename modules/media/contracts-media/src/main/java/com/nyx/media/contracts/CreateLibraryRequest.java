package com.nyx.media.contracts;

import java.util.List;

public record CreateLibraryRequest(
    String name,
    String description,
    LibraryType type,
    List<LibrarySourceRootWriteRequest> sourceRoots
) {
    public CreateLibraryRequest(String name, LibraryType type, List<LibrarySourceRootWriteRequest> sourceRoots) {
        this(name, "", type, sourceRoots);
    }

    public CreateLibraryRequest {
        if (description == null) {
            description = "";
        }
        sourceRoots = ContractCollections.immutableList(sourceRoots);
    }
}
