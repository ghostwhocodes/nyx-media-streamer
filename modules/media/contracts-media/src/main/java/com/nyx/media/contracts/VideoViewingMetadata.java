package com.nyx.media.contracts;

public record VideoViewingMetadata(
    TrickplayDiscoveryMetadata trickplay
) {
    public VideoViewingMetadata() {
        this(null);
    }
}
