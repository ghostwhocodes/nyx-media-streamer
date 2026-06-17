package com.nyx.media;

import java.util.Objects;

public record LibraryRepairResult(
    String libraryId,
    int itemCount,
    LibraryExtensionRunSummary extensionSummary
) {
    public LibraryRepairResult(String libraryId, int itemCount) {
        this(libraryId, itemCount, new LibraryExtensionRunSummary());
    }

    public LibraryRepairResult {
        Objects.requireNonNull(libraryId, "libraryId");
        extensionSummary = extensionSummary == null ? new LibraryExtensionRunSummary() : extensionSummary;
    }
}
