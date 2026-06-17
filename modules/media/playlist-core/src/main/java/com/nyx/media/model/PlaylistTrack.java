package com.nyx.media.model;

import java.time.Instant;

public record PlaylistTrack(
    String id,
    String trackPath,
    int position,
    Instant addedAt
) {
}
