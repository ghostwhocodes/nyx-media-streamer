package com.nyx.media;

public record LibraryTrackedObjectCounts(
    int active,
    int missing,
    int orphaned
) {
}
