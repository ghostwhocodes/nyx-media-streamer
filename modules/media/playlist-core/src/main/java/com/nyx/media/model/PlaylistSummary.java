package com.nyx.media.model;

import java.time.Instant;
import java.util.Objects;

public record PlaylistSummary(
    String id,
    String name,
    String description,
    int trackCount,
    Instant createdAt,
    Instant updatedAt
) {
    public PlaylistSummary(String id, String name, int trackCount, Instant createdAt, Instant updatedAt) {
        this(id, name, "", trackCount, createdAt, updatedAt);
    }

    public PlaylistSummary {
        description = Objects.requireNonNullElse(description, "");
    }
}
