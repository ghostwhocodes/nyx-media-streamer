package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlaybackClientProfile(
    String id,
    PlaybackCapabilitySet capabilities,
    PlaybackConstraints constraints
) {
    public PlaybackClientProfile(String id) {
        this(id, new PlaybackCapabilitySet(), new PlaybackConstraints());
    }

    public PlaybackClientProfile {
        capabilities = capabilities == null ? new PlaybackCapabilitySet() : capabilities;
        constraints = constraints == null ? new PlaybackConstraints() : constraints;
    }
}
