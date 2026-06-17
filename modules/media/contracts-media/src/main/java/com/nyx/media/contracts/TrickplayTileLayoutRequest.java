package com.nyx.media.contracts;

public record TrickplayTileLayoutRequest(
    Integer columns,
    Integer rows
) {
    public TrickplayTileLayoutRequest() {
        this(null, null);
    }
}
