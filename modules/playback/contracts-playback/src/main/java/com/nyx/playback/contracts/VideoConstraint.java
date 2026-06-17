package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record VideoConstraint(
    Integer maxWidth,
    Integer maxHeight,
    Integer maxBitrateKbps
) {
    public VideoConstraint() {
        this(null, null, null);
    }
}
