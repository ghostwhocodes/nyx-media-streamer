package com.nyx.media;

public record LibraryOrphanedRecord(
    String libraryEntryId,
    String objectId,
    String primaryPath,
    LibraryTrackedObjectStatus status
) {
}
