package com.nyx.media.contracts;

public record LibraryItemUserStateEntry(
    LibraryItem item,
    LibraryItemUserState state
) {
}
