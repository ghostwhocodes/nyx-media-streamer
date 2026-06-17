package com.nyx.media.model;

import java.util.List;
import java.util.Objects;

public record UpsertChapterSetRequest(
    String mediaPath,
    String title,
    List<UpsertChapterMarkRequest> marks
) {
    public UpsertChapterSetRequest(String mediaPath) {
        this(mediaPath, "", List.of());
    }

    public UpsertChapterSetRequest(String mediaPath, String title) {
        this(mediaPath, title, List.of());
    }

    public UpsertChapterSetRequest {
        mediaPath = Objects.requireNonNull(mediaPath, "mediaPath");
        title = title == null ? "" : title;
        marks = marks == null ? List.of() : List.copyOf(marks);
    }
}
