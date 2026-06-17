package com.nyx.media.contracts;

public record LibraryItemUserState(
    boolean watched,
    boolean favorite,
    Long resumePositionMillis,
    int playCount,
    String lastPlayedAt,
    String lastInteractionAt,
    int leafCount,
    int watchedLeafCount,
    int favoriteLeafCount,
    int inProgressLeafCount,
    String resumeItemId,
    String resumeObjectId
) {
    public LibraryItemUserState() {
        this(false, false, null, 0, null, null, 0, 0, 0, 0, null, null);
    }
}
