package com.nyx.media.contracts;

public record TrickplayDiscoveryMetadata(
    TrickplayRequest defaultRequest,
    Boolean cacheableByDefault
) {
    public TrickplayDiscoveryMetadata() {
        this(new TrickplayRequest(), true);
    }

    public TrickplayDiscoveryMetadata {
        if (defaultRequest == null) {
            defaultRequest = new TrickplayRequest();
        }
        if (cacheableByDefault == null) {
            cacheableByDefault = true;
        }
    }
}
