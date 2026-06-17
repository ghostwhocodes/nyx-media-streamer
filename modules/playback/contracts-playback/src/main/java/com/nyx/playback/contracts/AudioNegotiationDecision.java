package com.nyx.playback.contracts;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AudioNegotiationDecision(
    AudioDeliveryMode mode,
    Set<PlaybackReason> reasons,
    AudioFormatDescriptor source,
    AudioFormatDescriptor output
) {
    public AudioNegotiationDecision(AudioDeliveryMode mode) {
        this(mode, Set.of(), null, null);
    }

    public AudioNegotiationDecision {
        reasons = PlaybackContractSupport.immutableSet(reasons);
    }
}
