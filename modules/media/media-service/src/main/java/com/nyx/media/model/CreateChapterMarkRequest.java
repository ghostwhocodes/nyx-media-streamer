package com.nyx.media.model;

import java.util.Objects;

public record CreateChapterMarkRequest(
    String mediaPath,
    String label,
    double ptsSecs,
    String notes
) {
    public CreateChapterMarkRequest(String mediaPath, String label, double ptsSecs) {
        this(mediaPath, label, ptsSecs, "");
    }

    public CreateChapterMarkRequest {
        mediaPath = Objects.requireNonNull(mediaPath, "mediaPath");
        label = Objects.requireNonNull(label, "label");
        notes = notes == null ? "" : notes;
    }
}
