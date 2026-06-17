package com.nyx.media.model;

import java.util.Objects;

public record UpdateChapterMarkRequest(
    String label,
    double ptsSecs,
    String notes,
    int sortOrder
) {
    public UpdateChapterMarkRequest(String label, double ptsSecs, int sortOrder) {
        this(label, ptsSecs, "", sortOrder);
    }

    public UpdateChapterMarkRequest {
        label = Objects.requireNonNull(label, "label");
        notes = notes == null ? "" : notes;
    }
}
