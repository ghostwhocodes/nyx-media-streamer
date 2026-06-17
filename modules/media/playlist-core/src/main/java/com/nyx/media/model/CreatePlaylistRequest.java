package com.nyx.media.model;

import java.util.List;
import java.util.Objects;

public record CreatePlaylistRequest(
    String name,
    String description,
    List<String> tracks
) {
    public CreatePlaylistRequest(String name) {
        this(name, "", List.of());
    }

    public CreatePlaylistRequest(String name, String description) {
        this(name, description, List.of());
    }

    public CreatePlaylistRequest {
        description = Objects.requireNonNullElse(description, "");
        tracks = List.copyOf(Objects.requireNonNullElse(tracks, List.of()));
    }
}
