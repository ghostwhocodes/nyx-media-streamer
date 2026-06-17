package com.nyx.media.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ChapterSet(
    String id,
    String mediaPath,
    String title,
    List<ChapterMark> marks,
    Instant createdAt,
    Instant updatedAt
) {
    public ChapterSet(String mediaPath) {
        this(null, mediaPath, "", List.of(), null, null);
    }

    public ChapterSet {
        mediaPath = Objects.requireNonNull(mediaPath, "mediaPath");
        title = title == null ? "" : title;
        marks = marks == null ? List.of() : List.copyOf(marks);
    }
}
