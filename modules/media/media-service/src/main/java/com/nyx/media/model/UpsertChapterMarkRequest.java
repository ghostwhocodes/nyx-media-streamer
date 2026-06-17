package com.nyx.media.model;

import java.util.Objects;

public record UpsertChapterMarkRequest(
    String id,
    String label,
    double ptsSecs,
    String notes,
    int sortOrder
) {
    public UpsertChapterMarkRequest(String label, double ptsSecs, String notes, int sortOrder) {
        this(null, label, ptsSecs, notes, sortOrder);
    }

    public UpsertChapterMarkRequest {
        label = Objects.requireNonNull(label, "label");
        notes = notes == null ? "" : notes;
    }
}
