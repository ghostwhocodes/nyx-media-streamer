package com.nyx.media;

import com.nyx.media.contracts.LibraryItemType;
import com.nyx.media.contracts.MediaKind;

public record LibraryItemDescriptor(
    String identityKey,
    String parentIdentityKey,
    String sourceEntryId,
    String sourceObjectId,
    LibraryItemType type,
    String title,
    MediaKind mediaKind,
    String primaryPath,
    String unmatchedReason,
    Integer seasonNumber,
    Integer episodeNumber,
    Integer trackNumber
) {
}
