package com.nyx.media.contracts;

import java.util.List;

public record LibraryItem(
    String libraryItemId,
    String libraryId,
    String parentItemId,
    String sourceEntryId,
    String sourceObjectId,
    LibraryItemType type,
    String title,
    int childCount,
    MediaKind mediaKind,
    String primaryPath,
    String unmatchedReason,
    String displayTitle,
    String sortTitle,
    String overview,
    List<String> tags,
    List<LibraryArtwork> artwork,
    List<LibraryCollectionSummary> collections,
    Integer seasonNumber,
    Integer episodeNumber,
    Integer trackNumber,
    String createdAt,
    String updatedAt
) {
    public LibraryItem(
        String libraryItemId,
        String libraryId,
        LibraryItemType type,
        String title,
        String createdAt,
        String updatedAt
    ) {
        this(
            libraryItemId,
            libraryId,
            null,
            null,
            null,
            type,
            title,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null,
            createdAt,
            updatedAt
        );
    }

    public LibraryItem {
        tags = ContractCollections.immutableList(tags);
        artwork = ContractCollections.immutableList(artwork);
        collections = ContractCollections.immutableList(collections);
    }
}
