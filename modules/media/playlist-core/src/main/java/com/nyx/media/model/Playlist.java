package com.nyx.media.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Playlist(
    String id,
    String name,
    String description,
    List<PlaylistTrack> tracks,
    Instant createdAt,
    Instant updatedAt
) {
    public Playlist(String id, String name, Instant createdAt, Instant updatedAt) {
        this(id, name, "", List.of(), createdAt, updatedAt);
    }

    public Playlist(String id, String name, String description, Instant createdAt, Instant updatedAt) {
        this(id, name, description, List.of(), createdAt, updatedAt);
    }

    public Playlist {
        description = Objects.requireNonNullElse(description, "");
        tracks = List.copyOf(Objects.requireNonNullElse(tracks, List.of()));
    }
}
