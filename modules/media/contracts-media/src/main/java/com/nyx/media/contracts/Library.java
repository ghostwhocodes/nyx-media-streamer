package com.nyx.media.contracts;

import java.util.List;

public record Library(
    String libraryId,
    String name,
    String description,
    LibraryType type,
    List<LibrarySourceRoot> sourceRoots,
    LibraryScanState scanState,
    String createdAt,
    String updatedAt
) {
    public Library(String libraryId, String name, LibraryType type, String createdAt, String updatedAt) {
        this(libraryId, name, "", type, List.of(), new LibraryScanState(), createdAt, updatedAt);
    }

    public Library {
        if (description == null) {
            description = "";
        }
        sourceRoots = ContractCollections.immutableList(sourceRoots);
        if (scanState == null) {
            scanState = new LibraryScanState();
        }
    }
}
