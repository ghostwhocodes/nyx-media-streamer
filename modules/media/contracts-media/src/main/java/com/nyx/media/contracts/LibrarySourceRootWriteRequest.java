package com.nyx.media.contracts;

public record LibrarySourceRootWriteRequest(
    String path,
    String displayName
) {
    public LibrarySourceRootWriteRequest(String path) {
        this(path, null);
    }
}
