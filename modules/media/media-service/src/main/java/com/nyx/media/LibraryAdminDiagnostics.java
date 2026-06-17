package com.nyx.media;

import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryItem;
import java.util.List;
import java.util.Objects;

public record LibraryAdminDiagnostics(
    Library library,
    LibraryScanRun latestRun,
    LibraryTrackedObjectCounts trackedObjects,
    LibraryItemDiagnostics items,
    List<LibraryItem> unmatchedItems,
    List<LibraryItem> genericItems,
    List<LibraryOrphanedRecord> orphanedRecords,
    RegisteredLibraryExtensions registeredExtensions
) {
    public LibraryAdminDiagnostics {
        Objects.requireNonNull(library, "library");
        Objects.requireNonNull(trackedObjects, "trackedObjects");
        Objects.requireNonNull(items, "items");
        unmatchedItems = unmatchedItems == null ? List.of() : List.copyOf(unmatchedItems);
        genericItems = genericItems == null ? List.of() : List.copyOf(genericItems);
        orphanedRecords = orphanedRecords == null ? List.of() : List.copyOf(orphanedRecords);
        registeredExtensions = registeredExtensions == null ? new RegisteredLibraryExtensions() : registeredExtensions;
    }
}
