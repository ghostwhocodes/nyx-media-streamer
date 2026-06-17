package com.nyx.playback.contracts;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AudioNegotiationRequest(
    MediaSourceRef source,
    long startPositionMillis,
    AudioClientIdentity client,
    AudioCapabilitySet capabilities,
    AudioConstraint constraints,
    AudioOutputPreferences output
) {
    public AudioNegotiationRequest(MediaSourceRef source) {
        this(source, 0L, null, new AudioCapabilitySet(), new AudioConstraint(), new AudioOutputPreferences());
    }

    public AudioNegotiationRequest {
        capabilities = capabilities == null ? new AudioCapabilitySet() : capabilities;
        constraints = constraints == null ? new AudioConstraint() : constraints;
        output = output == null ? new AudioOutputPreferences() : output;
    }
}
