package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AudioTrackSelection(
    AudioTrackSelectionMode mode,
    List<Integer> trackIndices
) {
    public AudioTrackSelection() {
        this(AudioTrackSelectionMode.ALL, List.of());
    }

    public AudioTrackSelection(AudioTrackSelectionMode mode) {
        this(mode, List.of());
    }

    public AudioTrackSelection {
        trackIndices = PlaybackContractSupport.immutableList(trackIndices);
    }
}
