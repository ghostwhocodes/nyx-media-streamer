package com.nyx.media.contracts;

public record LibrarySourceRoot(
    String sourceRootId,
    String path,
    String displayName,
    int position,
    String createdAt,
    String updatedAt
) {
}
