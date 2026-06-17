package com.nyx.media;

import java.util.Objects;

public record LibraryScanRequest(
    LibraryScanMode mode
) {
    public LibraryScanRequest {
        mode = Objects.requireNonNull(mode, "mode");
    }

    public LibraryScanMode getMode() {
        return mode;
    }
}
