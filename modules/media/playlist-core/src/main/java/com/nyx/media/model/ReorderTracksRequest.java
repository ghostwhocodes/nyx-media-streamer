package com.nyx.media.model;

import java.util.List;
import java.util.Objects;

public record ReorderTracksRequest(
    List<String> trackIds
) {
    public ReorderTracksRequest {
        trackIds = List.copyOf(Objects.requireNonNullElse(trackIds, List.of()));
    }
}
