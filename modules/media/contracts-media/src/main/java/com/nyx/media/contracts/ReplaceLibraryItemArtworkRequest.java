package com.nyx.media.contracts;

public record ReplaceLibraryItemArtworkRequest(
    String posterPath,
    String backgroundPath,
    String thumbnailPath
) {
    public ReplaceLibraryItemArtworkRequest() {
        this(null, null, null);
    }
}
