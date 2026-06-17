package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlaybackOutputSummary(
    String videoCodec,
    Set<String> audioCodecs
) {
    public PlaybackOutputSummary() {
        this(null, Set.of());
    }

    public PlaybackOutputSummary {
        audioCodecs = PlaybackContractSupport.immutableSet(audioCodecs);
    }
}
