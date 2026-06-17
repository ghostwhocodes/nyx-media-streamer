package com.nyx.transcode;

import java.util.Objects;

public record SegmentInfo(
    String name,
    String representationId,
    double durationSecs,
    int index
) {
    public SegmentInfo {
        name = Objects.requireNonNull(name, "name");
        representationId = Objects.requireNonNull(representationId, "representationId");
    }

    public String getName() {
        return name;
    }

    public String getRepresentationId() {
        return representationId;
    }

    public double getDurationSecs() {
        return durationSecs;
    }

    public int getIndex() {
        return index;
    }
}
