package com.nyx.media.model;

import java.util.List;

public record UpdatePlaylistRequest(
    String name,
    String description,
    List<String> tracks
) {
    public UpdatePlaylistRequest() {
        this(null, null, null);
    }
}
