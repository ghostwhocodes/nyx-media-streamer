package com.nyx.media.model;

import java.time.Instant;
import java.util.Objects;

public record ChapterMark(
    String id,
    String label,
    double ptsSecs,
    String notes,
    int sortOrder,
    Instant createdAt,
    Instant updatedAt
) {
    public ChapterMark {
        id = Objects.requireNonNull(id, "id");
        label = Objects.requireNonNull(label, "label");
        notes = notes == null ? "" : notes;
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
