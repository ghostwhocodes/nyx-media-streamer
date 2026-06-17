package com.nyx.media.contracts;

public record LibraryArtwork(
    LibraryArtworkKind kind,
    LibraryArtworkSource source,
    String path,
    String mimeType
) {
}
