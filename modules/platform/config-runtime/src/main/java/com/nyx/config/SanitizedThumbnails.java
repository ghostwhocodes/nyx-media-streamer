package com.nyx.config;

import java.util.List;

public record SanitizedThumbnails(
    List<Integer> sizes,
    int videoOffsetPercent,
    long maxCacheSizeMB
) {
    public SanitizedThumbnails {
        sizes = List.copyOf(sizes);
    }
}
